package dev.pymdk.mapper.impl;

import dev.pymdk.mapper.impl.MappingEntries.MappedClass;
import dev.pymdk.mapper.impl.MappingEntries.MappedField;
import dev.pymdk.mapper.impl.MappingEntries.MappedMember;
import dev.pymdk.mapper.impl.SignatureMapper.AttributeHolder;
import dev.pymdk.mapper.impl.helpers.ConstantPool;
import dev.pymdk.mapper.impl.helpers.Constants;
import dev.pymdk.mapper.impl.helpers.GrowableByteBuffer;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static dev.pymdk.mapper.impl.LowLevelMapper.*;
import static dev.pymdk.mapper.impl.SignatureMapper.AttributeHolder.*;
import static dev.pymdk.mapper.impl.helpers.ConstantPool.Utf8CacheType.DESCRIPTOR_CACHE;
import static dev.pymdk.mapper.impl.helpers.ConstantPool.Utf8CacheType.NAME_CACHE;

/**
 * :D
 */
public class ClassMapper implements Constants {

	private static final HashSet<String> UNRECOGNIZED_ATTRIBUTES = new HashSet<>();

	private static final MappedClass UNMAPPED = new MappedClass(null, null) {{
		create();
	}};
	private static final MappedClass BOOTSTRAP_MTD_OWNER = new MappedClass(null, null) {{
		create();
	}};

	/**
	 * The input.
	 */
	private final byte[] content;

	/**
	 * The output.
	 */
	private final GrowableByteBuffer output;

	/**
	 * The size of the original constant pool.
	 */
	private final short poolCount;

	/**
	 * The {@link #content} position of the constant pool end.
	 */
	private int constantPoolEnd;

	/**
	 * Overwrites relative to the constant pool end.
	 */
	private final List<OverwriteRel> overwrites = new ArrayList<>();

	/**
	 * pool index -> {@link #content} position.
	 */
	private final int[] startIndices;

	/**
	 * pool index -> tag (entry type).
	 *
	 * @see Constants
	 */
	private final byte[] tags;

	/**
	 * pool index -> resolved class.
	 */
	private final MappedClass[] classCache;

	/**
	 * The new, mapped constant pool.
	 */
	private final ConstantPool pool;

	public ClassMapper(byte[] content, @NotNull GrowableByteBuffer output) {
		this.content = content;
		this.output = output;

		int rawPoolCount = readShort(CONSTANT_POOL_SIZE_OFFSET) & 0xFFFF;
		if (rawPoolCount > Short.MAX_VALUE)
			throw new UnsupportedOperationException("Constant pools with more than " + Short.MAX_VALUE + " entries are not supported");

		poolCount = (short) rawPoolCount;
		startIndices = new int[poolCount + 1];
		tags = new byte[poolCount];
		classCache = new MappedClass[poolCount];
		pool = new ConstantPool(poolCount);
	}

	/**
	 * Maps a class file by decoding, mapping and rebuilding the constant pool.
	 *
	 * @implNote We can not map constant pool entries in-place as a CONSTANT_Utf8_info or
	 * CONSTANT_NameAndType_info entry may have multiple meanings. For
	 * example, in Minecraft 1.8.9 the ave.class contains a CONSTANT_Utf8_info
	 * with the text {@code J} that is used as obfuscated name for the {@code prevFrameTime} field and
	 * as descriptor ({@code long} field). This is not the default behavior of the Java compiler
	 * and likely a result of ProGuard minification.
	 */
	public void map() {
		// Decode content pool
		int idx, cursor = CONSTANT_POOL_SIZE_OFFSET + 2;
		for (idx = 1; idx < poolCount; idx++) {
			startIndices[idx] = cursor;
			byte tag = content[cursor++];
			tags[idx] = tag;
			switch (tag) {
				case CONSTANT_UTF8 -> {
					short length = (short) (((content[cursor++] & 0xFF) << 8) | (content[cursor++] & 0xFF));
					String text = new String(content, cursor, length, StandardCharsets.UTF_8);
					pool.cacheUtf8(text, (short) idx);
					cursor += length;
				}
				case CONSTANT_CLASS, CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE ->
						cursor += 2;
				case CONSTANT_METHOD_HANDLE -> cursor += 3;
				case CONSTANT_NAME_AND_TYPE -> {
					short nameIndex = readShort(cursor);
					short descIndex = readShort(cursor + 2);
					pool.cacheNameAndType(nameIndex, descIndex, (short) idx);
					cursor += 4;
				}
				case CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF,
				     CONSTANT_INTERFACE_METHODREF, CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> cursor += 4;
				case CONSTANT_LONG, CONSTANT_DOUBLE -> {
					// CONSTANT_Long_info / CONSTANT_Double_info take two entries
					cursor += 8;
					idx++;
					tags[idx] = tag;
					startIndices[idx] = cursor;
				}
				default -> throw new IllegalStateException("Invalid constant pool tag " + tag);
			}
		}
		startIndices[idx] = cursor;
		constantPoolEnd = cursor;

		short thisClassIdx = readShort(cursor + 2);
		MappedClass thisClass = mapClass(thisClassIdx);

		short interfacesCount = readShort(cursor + 6);
		cursor += interfacesCount * 2 + 8;

		// Decode fields, methods
		for (int block = 0; block < 2; block++) {
			boolean isField = block == 0;
			short count = readShort(cursor);
			cursor += 2;
			for (int memberIdx = 0; memberIdx < count; memberIdx++) {
				short nameIndex = readShort(cursor + 2);
				short descIndex = readShort(cursor + 4);
				String name = readUtf8FromPool(nameIndex);
				String desc = readUtf8FromPool(descIndex);

				MappedMember member;
				if (isField)
					member = thisClass.fields.get(name, SOURCE_MAPPING);
				else
					member = thisClass.methods.get(name + desc, SOURCE_MAPPING);

				short newNameIndex, newDescIndex;
				if (member == null) {
					newNameIndex = pool.insertUtf8Cached(name, nameIndex, NAME_CACHE);
					newDescIndex = pool.insertUtf8CachedDesc(desc, isField, descIndex);
				} else {
					newNameIndex = pool.insertUtf8Cached(member.getName(TARGET_MAPPING), nameIndex, NAME_CACHE);
					newDescIndex = pool.insertUtf8Cached(member.getDesc(TARGET_MAPPING), descIndex, DESCRIPTOR_CACHE);
				}
				overwriteAbs(cursor + 2, newNameIndex);
				overwriteAbs(cursor + 4, newDescIndex);

				short attributesCount = readShort(cursor + 6);
				cursor += 8;
				for (int attrIdx = 0; attrIdx < attributesCount; attrIdx++)
					cursor += readAndMapAttributeInfo(cursor, isField ? FIELD : METHOD);
			}
		}

		short attributesCount = readShort(cursor);
		cursor += 2;
		for (int j = 0; j < attributesCount; j++)
			cursor += readAndMapAttributeInfo(cursor, CLASS);

		// Apply mappings
		for (idx = 1; idx < poolCount; idx++) {
			switch (tags[idx]) {
				// Map in-place
				case CONSTANT_CLASS -> {
					MappedClass map = mapClass((short) idx);
					if (map != UNMAPPED) {
						String name = map.getName(TARGET_MAPPING);
						short nameIndex = pool.insertUtf8(name);
						writeShort(startIndices[idx] + 1, nameIndex);
					}
				}
				case CONSTANT_METHODREF, CONSTANT_FIELDREF, CONSTANT_INTERFACE_METHODREF -> {
					int startCursor = startIndices[idx];
					short classIndex = readShort(startCursor + 1);
					short nameAndTypeIndex = readShort(startCursor + 3);
					short newNameAndTypeIndex = mapAndInsertNameAndType(classIndex, nameAndTypeIndex, tags[idx] == CONSTANT_FIELDREF);
					writeShort(startCursor + 3, newNameAndTypeIndex);
				}
				case CONSTANT_INVOKE_DYNAMIC -> {
					int startCursor = startIndices[idx];
					short nameAndTypeIndex = readShort(startCursor + 3);
					short newNameAndTypeIndex = mapAndInsertNameAndType(BOOTSTRAP_MTD_OWNER, nameAndTypeIndex, false);
					writeShort(startCursor + 3, newNameAndTypeIndex);
				}
				case CONSTANT_METHOD_TYPE -> {
					int startCursor = startIndices[idx];
					short descIndex = readShort(startCursor + 1);
					short newDescIndex = pool.insertUtf8CachedDesc(readUtf8FromPool(descIndex), false, descIndex);
					writeShort(startCursor + 1, newDescIndex);
				}

				// Direct copy
				case CONSTANT_DYNAMIC, CONSTANT_METHOD_HANDLE -> {
					// No need to map as Minecraft does not contain any bootstrap methods, so all entries are unobfuscated
				}
				case CONSTANT_UTF8, CONSTANT_NAME_AND_TYPE, CONSTANT_LONG, CONSTANT_DOUBLE, CONSTANT_STRING,
				     CONSTANT_INTEGER, CONSTANT_FLOAT -> {
					// No-op
				}
				default -> throw new IllegalStateException("Invalid constant pool tag " + tags[idx]);
			}
		}

		// Copy constant pool
		output.copyFrom(content, 0, constantPoolEnd);

		// Write new content pool
		int constantPoolSize = pool.writeTo(output);
		output.overwriteShort(CONSTANT_POOL_SIZE_OFFSET, constantPoolSize);

		// Copy everything else
		int constantPoolOutEnd = output.cursor;
		output.copyFrom(content, constantPoolEnd, content.length - constantPoolEnd);

		// Apply overwrites
		for (OverwriteRel overwrite : overwrites)
			output.overwriteShort(constantPoolOutEnd + overwrite.relCursor, overwrite.value);
	}

	/**
	 * Maps a CONSTANT_Class_info entry.
	 */
	private @NotNull MappedClass mapClass(short classIndex) {
		if (classCache[classIndex] != null)
			return classCache[classIndex];

		short nameIndex = readShort(startIndices[classIndex] + 1);
		dbgAssert(tags[classIndex] == CONSTANT_CLASS);

		String name = readUtf8FromPool(nameIndex);
		MappedClass mappedClass = LowLevelMapper.classes.get(name, SOURCE_MAPPING);
		if (mappedClass == null)
			// Not mapped
			return classCache[classIndex] = UNMAPPED;

		classCache[classIndex] = mappedClass;
		return mappedClass;
	}

	/**
	 * Maps a CONSTANT_NameAndType_info entry and inserts it into the new constant pool.
	 *
	 * @param classIndex       Constant pool index of CONSTANT_Class_info entry.
	 * @param nameAndTypeIndex Constant pool index of CONSTANT_NameAndType_info entry.
	 * @param isField              true if the CONSTANT_NameAndType_info references a field, false otherwise.
	 * @return The position of the mapped entry.
	 */
	private short mapAndInsertNameAndType(short classIndex, short nameAndTypeIndex, boolean isField) {
		dbgAssert(tags[classIndex] == CONSTANT_CLASS);

		MappedClass mappedClass = mapClass(classIndex);
		return mapAndInsertNameAndType(mappedClass, nameAndTypeIndex, isField);
	}

	/**
	 * Maps a CONSTANT_NameAndType_info entry and inserts it into the new constant pool.
	 *
	 * @param nameAndTypeIndex Constant pool index of CONSTANT_NameAndType_info entry.
	 * @param isField              true if the CONSTANT_NameAndType_info references a field, false otherwise.
	 * @return The position of the mapped entry.
	 */
	private short mapAndInsertNameAndType(MappedClass mappedClass, short nameAndTypeIndex, boolean isField) {
		dbgAssert(tags[nameAndTypeIndex] == CONSTANT_NAME_AND_TYPE);

		int nameAndTypeCursor = startIndices[nameAndTypeIndex];
		short nameIndex = readShort(nameAndTypeCursor + 1);
		short descIndex = readShort(nameAndTypeCursor + 3);

		String name = readUtf8FromPool(nameIndex);
		String desc = readUtf8FromPool(descIndex);
		short newNameIndex, newDescIndex;

		if (mappedClass == UNMAPPED) {
			newNameIndex = pool.insertUtf8Cached(name, nameIndex, NAME_CACHE);
			newDescIndex = pool.insertUtf8CachedDesc(desc, isField, descIndex);
		} else {
			MappedMember member = null;
			if (isField)
				member = mappedClass.getFieldRecursive(name, SOURCE_MAPPING);
			else {
				if (!name.equals("<init>"))
					member = mappedClass.getMethodRecursive(name + desc, SOURCE_MAPPING);
			}

			if (member == null) {
				newNameIndex = pool.insertUtf8Cached(name, nameIndex, NAME_CACHE);
				newDescIndex = pool.insertUtf8CachedDesc(desc, isField, descIndex);
			} else {
				newNameIndex = pool.insertUtf8(member.getName(TARGET_MAPPING));
				newDescIndex = pool.insertUtf8Cached(member.getDesc(TARGET_MAPPING), descIndex, DESCRIPTOR_CACHE);
			}
		}

		return pool.insertNameAndType(newNameIndex, newDescIndex);
	}

	/**
	 * Reads an attribute and maps CONSTANT_NameAndType_info and CONSTANT_Utf8_info referenced to by the attribute.
	 *
	 * @return The full size of the attribute.
	 */
	private int readAndMapAttributeInfo(int cursor, @NotNull AttributeHolder attributeHolder) {
		short attributeNameIndex = readShort(cursor);
		int attributeLength = readInt(cursor + 2);
		if (tags[attributeNameIndex] != CONSTANT_UTF8)
			return attributeLength + 6;

		String attrName = readUtf8FromPool(attributeNameIndex);
		switch (attrName) {
			case "ConstantValue", "SourceFile", "LineNumberTable", "StackMapTable", "Exceptions", "NestHost",
			     "NestMembers" -> {
				// Doesn't contain anything that must be mapped
			}
			case "BootstrapMethods" -> {
				// No need to map as Minecraft does not contain any bootstrap methods, so all entries are unobfuscated
			}
			case "Signature" -> {
				short signatureIndex = readShort(cursor + 6);
				String signature = readUtf8FromPool(signatureIndex);
				short newSignatureIndex = pool.insertUtf8(SignatureMapper.mapSignature(signature, attributeHolder));
				overwriteAbs(cursor + 6, newSignatureIndex);
			}
			case "Code" -> {
				cursor += 10;
				int codeLength = readInt(cursor);
				cursor += 4 + codeLength;
				short exceptionTableLength = readShort(cursor);
				cursor += 2 + exceptionTableLength * 8;
				short attributesCount = readShort(cursor);
				cursor += 2;
				for (int attrIdx = 0; attrIdx < attributesCount; attrIdx++)
					cursor += readAndMapAttributeInfo(cursor, CODE);
			}
			case "LocalVariableTable" -> {
				short lvtLength = readShort(cursor + 6);
				cursor += 8;
				for (int varIdx = 0; varIdx < lvtLength; varIdx++) {
					short descriptorIndex = readShort(cursor + 6);

					String descriptor = readUtf8FromPool(descriptorIndex);
					short newDescIndex = pool.insertUtf8CachedDesc(descriptor, true, descriptorIndex);
					overwriteAbs(cursor + 6, newDescIndex);

					cursor += 10;
				}
			}
			case "LocalVariableTypeTable" -> {
				short lvtLength = readShort(cursor + 6);
				cursor += 8;
				for (int varIdx = 0; varIdx < lvtLength; varIdx++) {
					short signatureIndex = readShort(cursor + 6);

					String signature = readUtf8FromPool(signatureIndex);
					short newSignatureIndex = pool.insertUtf8(SignatureMapper.mapSignature(signature, FIELD));
					overwriteAbs(cursor + 6, newSignatureIndex);

					cursor += 10;
				}
			}
			case "InnerClasses" -> {
				short numberOfClasses = readShort(cursor + 6);
				cursor += 8;
				for (int classIdx = 0; classIdx < numberOfClasses; classIdx++) {
					short innerNameIndex = readShort(cursor + 4);
					if (innerNameIndex != 0) {
						short innerClassInfoIndex = readShort(cursor);
						MappedClass outerClassInfo = mapClass(innerClassInfoIndex);
						if (outerClassInfo != UNMAPPED) {
							String name = outerClassInfo.getName(TARGET_MAPPING);
							String innerName = name.substring(name.lastIndexOf('$') + 1);
							overwriteAbs(cursor + 4, pool.insertUtf8(innerName));
						}
					}
					cursor += 8;
				}
			}
			case "EnclosingMethod" -> {
				short classIndex = readShort(cursor + 6);
				short nameAndTypeIndex = readShort(cursor + 8);
				if (nameAndTypeIndex != 0) {
					short newNameAndTypeIndex = mapAndInsertNameAndType(classIndex, nameAndTypeIndex, false);
					overwriteAbs(cursor + 8, newNameAndTypeIndex);
				}
			}
			case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> {
				short numAnnotations = readShort(cursor + 6);
				cursor += 8;
				for (int i = 0; i < numAnnotations; i++)
					cursor = readAndMapAnnotation(cursor);
			}
			case "AnnotationDefault" -> readAndMapElementValue(cursor + 6);
			default -> {
				if (UNRECOGNIZED_ATTRIBUTES.add(attrName))
					System.err.println("Unimplemented attribute " + attrName);
			}
		}

		return attributeLength + 6;
	}

	/**
	 * Maps an annotation entry.
	 *
	 * @return The new cursor position.
	 */
	private int readAndMapAnnotation(int cursor) {
		short numElementValuePairs = readShort(cursor + 2);
		cursor += 4;

		for (int j = 0; j < numElementValuePairs; j++)
			cursor = readAndMapElementValue(cursor + 2);

		return cursor;
	}

	/**
	 * Maps an element_value entry.
	 *
	 * @return The new cursor position.
	 */
	private int readAndMapElementValue(int cursor) {
		byte tag = content[cursor++];
		// Only map classes
		return switch (tag) {
			case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> cursor + 2; // const_value_index
			case 'e' -> {
				// enum_const_value
				short typeNameIndex = readShort(cursor);
				String desc = readUtf8FromPool(typeNameIndex);
				if (desc.charAt(0) == 'L') {
					// Map class
					String internalName = desc.substring(1, desc.length() - 1);
					MappedClass mappedClass = LowLevelMapper.classes.get(internalName, SOURCE_MAPPING);
					if (mappedClass != null) {
						String newDesc = "L" + mappedClass.getName(TARGET_MAPPING) + ";";
						short newDescIndex = pool.insertUtf8Cached(newDesc, typeNameIndex, NAME_CACHE);
						overwriteAbs(cursor + 2, newDescIndex);

						// Map field
						short constNameIndex = readShort(cursor + 2);
						String name = readUtf8FromPool(constNameIndex);
						MappedField mappedField = mappedClass.getFieldRecursive(name, SOURCE_MAPPING);
						if (mappedField != null) {
							String newName = mappedField.getName(TARGET_MAPPING);
							short newNameIndex = pool.insertUtf8Cached(newName, constNameIndex, NAME_CACHE);
							overwriteAbs(cursor + 2, newNameIndex);
						}
					}
				}

				yield cursor + 4;
			}
			case 'c' -> {
				// class_info_index
				short descIndex = readShort(cursor);
				String desc = readUtf8FromPool(descIndex);
				String mappedDesc = LowLevelMapper.mapStandaloneFieldDesc(desc);
				short newDescIndex = pool.insertUtf8CachedDesc(mappedDesc, true, descIndex);
				overwriteAbs(cursor, newDescIndex);
				yield cursor + 2;
			}
			case '@' -> readAndMapAnnotation(cursor); // annotation_value
			case '[' -> {
				// array_value
				short numValues = readShort(cursor);
				cursor += 2;
				for (int i = 0; i < numValues; i++)
					cursor = readAndMapElementValue(cursor);

				yield cursor;
			}
			default -> throw new IllegalStateException("Illegal ElementValue tag " + tag);
		};
	}

	private short readShort(int cursor) {
		return (short) (((content[cursor] & 0xFF) << 8) | (content[cursor + 1] & 0xFF));
	}

	private void writeShort(int cursor, short value) {
		content[cursor++] = (byte) (value >>> 8 & 0xFF);
		content[cursor] = (byte) (value & 0xFF);
	}

	private int readInt(int cursor) {
		return (((content[cursor] & 0xFF) << 24)
				| ((content[cursor + 1] & 0xFF) << 16)
				| ((content[cursor + 2] & 0xFF) << 8)
				| (content[cursor + 3] & 0xFF));
	}

	/**
	 * Read the text from an existing CONSTANT_Utf8_info entry.
	 *
	 * @param index The index of the entry in the source constant pool.
	 */
	private @NotNull String readUtf8FromPool(short index) {
		dbgAssert(tags[index] == CONSTANT_UTF8);

		int cursor = startIndices[index] + 1;
		short length = (short) (((content[cursor++] & 0xFF) << 8) | (content[cursor++] & 0xFF));
		return new String(content, cursor, length, StandardCharsets.UTF_8);
	}

	/**
	 * Overwrites a value that is positioned after the constant pool.
	 */
	private void overwriteAbs(int absCursor, short value) {
		overwrites.add(new OverwriteRel(absCursor - constantPoolEnd, value));
	}

	private record OverwriteRel(int relCursor, short value) {}

}
