package dev.pymdk.mapper;

import dev.pymdk.mapper.MappingData.ClassMapping;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;

import static org.objectweb.asm.Opcodes.H_PUTSTATIC;

public record Mapper(MappingData mappings, AugmentationData augmentations) {

	public String mapClassName(String oldName) {
		if (oldName == null)
			return null;

		ClassMapping mapping = mappings.getClassMapping(oldName);
		if (mapping == null)
			return oldName;

		return mapping.name();
	}

	public String mapInternalName(String internalName) {
		if (internalName.startsWith("[")) // Arrays
			return mapDescOrSig(internalName);

		return mapClassName(internalName);
	}

	/**
	 * Maps all references to classes in a method descriptor / signature.
	 */
	public String mapDescOrSig(String desc) {
		if (desc == null)
			return null;

		StringBuilder builder = new StringBuilder();

		boolean enteredObject = false;
		StringBuilder nameBuffer = new StringBuilder();
		for (char c : desc.toCharArray()) {
			if (enteredObject) {
				if (c == ';' || c == '<') {
					enteredObject = false;

					builder.append(mapClassName(nameBuffer.toString()));
					builder.append(c);
					nameBuffer.setLength(0);
				} else
					nameBuffer.append(c);

				continue;
			}

			if (c == 'L')
				enteredObject = true;

			builder.append(c);
		}

		return builder.toString();
	}

	/**
	 * Maps the class referenced to by the given {@link Type}.
	 */
	public Type mapType(Type type) {
		return Type.getType(mapDescOrSig(type.getDescriptor()));
	}

	/**
	 * Maps the field / method referenced to by the given {@link Handle}.
	 */
	public Handle mapHandle(Handle handle) {
		ClassMapping mapping = mappings.getClassMapping(handle.getOwner());

		String name = handle.getName();
		if (mapping != null) {
			if (handle.getTag() <= H_PUTSTATIC)
				name = mapping.mapField(name);
			else
				name = mapping.mapMethod(name, handle.getDesc());
		}

		return new Handle(
				handle.getTag(),
				mapping == null ? handle.getOwner() : mapping.name(),
				name,
				mapDescOrSig(handle.getDesc()),
				handle.isInterface()
		);
	}

	/**
	 * Maps everything in a {@link ClassNode}.
	 */
	public void map(ClassNode classNode) {
		ClassMapping cm = mappings.getClassMapping(classNode.name);
		AugmentationData.ClassAugmentation ca = cm == null ? null : augmentations.classes().get(cm.name());

		classNode.superName = mapClassName(classNode.superName);
		classNode.interfaces.replaceAll(this::mapClassName);
		classNode.signature = mapDescOrSig(classNode.signature);

		if (cm != null)
			classNode.name = cm.name();

		// Map outer class
		if (classNode.outerClass != null) {
			if (classNode.outerMethodDesc != null)
				classNode.outerMethodDesc = mapDescOrSig(classNode.outerMethodDesc);

			ClassMapping outerMapping = mappings.getClassMapping(classNode.outerClass);
			if (outerMapping != null) {
				classNode.outerClass = outerMapping.name();
				if (classNode.outerMethod != null)
					classNode.outerMethod = outerMapping.mapMethod(classNode.outerMethod, classNode.outerMethodDesc);
			}
		}

		// Map inner classes
		for (InnerClassNode innerClass : classNode.innerClasses) {
			ClassMapping innerMapping = mappings.getClassMapping(innerClass.name);
			if (innerMapping == null)
				continue;

			innerClass.name = innerMapping.name();
			innerClass.outerName = mapClassName(innerClass.outerName);

			if (innerClass.innerName != null) {
				int dollar = innerClass.name.lastIndexOf('$');
				if (dollar == -1)
					continue;

				innerClass.innerName = innerClass.name.substring(dollar + 1);
			}
		}

		// Map module stuff
		classNode.nestHostClass = mapClassName(classNode.nestHostClass);
		if (classNode.nestMembers != null)
			classNode.nestMembers.replaceAll(this::mapClassName);

		if (classNode.permittedSubclasses != null)
			classNode.permittedSubclasses.replaceAll(this::mapClassName);

		// Map fields
		for (FieldNode field : classNode.fields) {
			field.signature = mapDescOrSig(field.signature);
			field.desc = mapDescOrSig(field.desc);

			if (cm != null)
				field.name = cm.mapField(field.name);
		}

		// Map methods
		for (MethodNode method : classNode.methods) {
			if (cm != null)
				method.name = cm.mapMethod(method.name, method.desc);

			method.exceptions.replaceAll(this::mapClassName);
			method.signature = mapDescOrSig(method.signature);
			method.desc = mapDescOrSig(method.desc);

			if (method.localVariables != null) {
				LocalNameGenerator nameGen = new LocalNameGenerator();
				method.localVariables.forEach(l -> {
					l.signature = mapDescOrSig(l.signature);
					l.desc = mapDescOrSig(l.desc);
					nameGen.rename(l);
				});
			}

			// Add parameter names
			Type[] parameterTypes = Type.getArgumentTypes(method.desc);
			if (ca != null && parameterTypes.length != 0) {
				AugmentationData.MethodAugmentation methodAug = ca.methods().get(new MappingData.Method(method.name, method.desc));
				if (methodAug != null) {
					int offset = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0; // this

					if (method.parameters == null) {
						method.parameters = new ArrayList<>(parameterTypes.length);
						for (int i = 0; i < parameterTypes.length; i++)
							method.parameters.add(new ParameterNode(null, 0));
					}

					for (int i = 0; i < parameterTypes.length; i++) {
						String name = methodAug.parameters().get((short) (i + offset));

						if (name != null)
							method.parameters.get(i).name = name;

						if (parameterTypes[i] == Type.LONG_TYPE || parameterTypes[i] == Type.DOUBLE_TYPE)
							offset++;
					}
				}
			}

			// Map method instructions
			method.instructions.forEach(this::mapInsn);
		}
	}

	/**
	 * Maps all targets referenced by the instruction.
	 */
	private void mapInsn(AbstractInsnNode insn) {
		switch (insn) {
			case TypeInsnNode typeInsn -> typeInsn.desc = mapInternalName(typeInsn.desc);
			case LdcInsnNode ldc -> {
				if (ldc.cst instanceof Type ty)
					ldc.cst = mapType(ty);
			}
			case MultiANewArrayInsnNode manaInsn -> manaInsn.desc = mapDescOrSig(manaInsn.desc);
			case FieldInsnNode fieldInsn -> {
				fieldInsn.desc = mapDescOrSig(fieldInsn.desc);
				ClassMapping classMapping = mappings.getClassMapping(fieldInsn.owner);
				if (classMapping != null) {
					fieldInsn.owner = classMapping.name();
					fieldInsn.name = classMapping.mapField(fieldInsn.name);
				}
			}
			case MethodInsnNode methodInsn -> {
				ClassMapping classMapping = mappings.getClassMapping(methodInsn.owner);
				if (classMapping != null) {
					methodInsn.owner = classMapping.name();
					methodInsn.name = classMapping.mapMethod(methodInsn.name, methodInsn.desc);
				}
				methodInsn.desc = mapDescOrSig(methodInsn.desc);
			}
			case InvokeDynamicInsnNode indyInsn -> {
				// Ignores some edge cases that don't happen in Minecraft's code

				// Map first arg if it's a Type
				Object firstArg = indyInsn.bsmArgs[0];
				if (firstArg instanceof Type owner) {
					ClassMapping classMapping = mappings.getClassMapping(owner.getInternalName());
					if (classMapping != null) {
						indyInsn.bsmArgs[0] = mapType(owner);
						indyInsn.name = classMapping.mapMethod(indyInsn.name, indyInsn.desc);
					}
				}

				indyInsn.desc = mapDescOrSig(indyInsn.desc);
				indyInsn.bsm = mapHandle(indyInsn.bsm);

				// Map remaining args
				for (int i = firstArg instanceof Type ? 1 : 0; i < indyInsn.bsmArgs.length; i++) {
					Object arg = indyInsn.bsmArgs[i];
					if (arg instanceof Type ty) {
						indyInsn.bsmArgs[i] = mapType(ty);
						continue;
					}

					if (!(arg instanceof Handle h))
						continue;

					indyInsn.bsmArgs[i] = mapHandle(h);
				}
			}

			case null, default -> {}
		}
	}

}
