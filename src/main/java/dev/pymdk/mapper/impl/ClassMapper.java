package dev.pymdk.mapper.impl;

import dev.pymdk.mapper.impl.helpers.Constants;
import dev.pymdk.mapper.Mapping;
import dev.pymdk.mapper.impl.MappingEntries.MappedClass;
import dev.pymdk.mapper.impl.MappingEntries.MappedMember;
import dev.pymdk.mapper.impl.helpers.ConstantPool;
import dev.pymdk.mapper.impl.helpers.GrowableByteBuffer;
import dev.pymdk.mapper.impl.SignatureMapper.AttributeHolder;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static dev.pymdk.mapper.impl.helpers.ConstantPool.Utf8CacheType.DESCRIPTOR_CACHE;
import static dev.pymdk.mapper.impl.helpers.ConstantPool.Utf8CacheType.NAME_CACHE;
import static dev.pymdk.mapper.impl.LowLevelMapper.*;
import static dev.pymdk.mapper.impl.SignatureMapper.AttributeHolder.*;

/**
 * :D
 */
public class ClassMapper implements Constants {

	private static final HashSet<String> UNRECOGNIZED_ATTRIBUTES = new HashSet<>();
	private static final MappedClass UNMAPPED = new MappedClass(null, null) {
		{
			create();
		}

		@Override
		public @NotNull String getName(@NotNull Mapping mapping) {
			throw new IllegalStateException("Cannot get name of unmapped class");
		}

		@Override
		public String toString() {
			return "UNMAPPED";
		}
	};

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

		int rawPoolCount = readShort(CONSTANT_POOL_SIZE_INDEX) & 0xFFFF;
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
		int idx, cursor = CONSTANT_POOL_SIZE_INDEX + 2;
		for (idx = 1; idx < poolCount; idx++) {
			startIndices[idx] = cursor;
			byte tag = content[cursor++];
			tags[idx] = tag;
			switch (tag) {
				case CONSTANT_UTF8 -> cursor += readShort(cursor) + 2;
				case CONSTANT_CLASS -> {
					pool.freeUtf8(readShort(cursor));
					cursor += 2;
				}
				case CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE -> cursor += 2;
				case CONSTANT_METHOD_HANDLE -> cursor += 3;
				case CONSTANT_NAME_AND_TYPE -> {
					// Do not free name as it may be referenced to by a CONSTANT_String_info
					pool.freeUtf8(readShort(cursor + 2));
					pool.freeNameAndType((short) idx);
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
				pool.freeUtf8(nameIndex);
				pool.freeUtf8(descIndex);
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
				case CONSTANT_UTF8, CONSTANT_NAME_AND_TYPE -> {
					int startIndex = startIndices[idx];
					int endIndex = startIndices[idx + 1];
					pool.copyIfUnset(content, startIndex, endIndex - startIndex);
				}
				case CONSTANT_CLASS -> {
					MappedClass map = mapClass((short) idx);
					short nameIndex;
					if (map == UNMAPPED) {
						short index = readShort(startIndices[idx] + 1);
						String name = readUtf8FromPool(index);
						nameIndex = pool.insertUtf8(name);
					} else {
						String name = map.getName(TARGET_MAPPING);
						nameIndex = pool.insertUtf8(name);
					}

					pool.addClassEntry(nameIndex);
				}
				case CONSTANT_METHODREF, CONSTANT_FIELDREF, CONSTANT_INTERFACE_METHODREF -> {
					int startIndex = startIndices[idx];
					short classIndex = readShort(startIndex + 1);
					short nameAndTypePoolIndex = readShort(startIndex + 3);
					short newNameAndTypePoolIndex = mapAndInsertNameAndType(classIndex, nameAndTypePoolIndex, tags[idx] == CONSTANT_FIELDREF);
					pool.addShortPairEntry(tags[idx], classIndex, newNameAndTypePoolIndex);
				}
				case CONSTANT_DYNAMIC, CONSTANT_INVOKE_DYNAMIC -> {
					int startIndex = startIndices[idx];
					short bootstrapMethodAttrIndex = readShort(startIndex + 1);
					short nameAndTypePoolIndex = readShort(startIndex + 3);
					dbgAssert(tags[nameAndTypePoolIndex] == CONSTANT_NAME_AND_TYPE);

					// Rebuild NameAndType
					int nameAndTypeIndex = startIndices[nameAndTypePoolIndex];
					int nameIndex = nameAndTypeIndex + 1; // position of namePoolIndex in content
					int descIndex = nameAndTypeIndex + 3; // position of descPoolIndex in content
					short namePoolIndex = readShort(nameIndex); // position of name entry in constant pool
					short descPoolIndex = readShort(descIndex); // position of descriptor entry in constant pool

					String name = readUtf8FromPool(namePoolIndex);
					String desc = readUtf8FromPool(descPoolIndex);
					short newNamePoolIndex, newDescPoolIndex;

					newNamePoolIndex = pool.insertUtf8Cached(name, namePoolIndex, NAME_CACHE);
					newDescPoolIndex = pool.insertUtf8CachedDesc(desc, false, descPoolIndex);

					short newNameAndTypePoolIndex = pool.insertNameAndType(newNamePoolIndex, newDescPoolIndex);
					pool.addShortPairEntry(tags[idx], bootstrapMethodAttrIndex, newNameAndTypePoolIndex);
				}

				// Direct copy
				case CONSTANT_LONG, CONSTANT_DOUBLE -> {
					int startIndex = startIndices[idx];
					int endIndex = startIndices[idx + 2];
					pool.doubleCopyFrom(content, startIndex, endIndex - startIndex);
					idx++;
				}
				case CONSTANT_STRING, CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_METHOD_HANDLE -> {
					int startIndex = startIndices[idx];
					int endIndex = startIndices[idx + 1];
					pool.copyFrom(content, startIndex, endIndex - startIndex);
				}
				default -> throw new IllegalStateException("Invalid constant pool tag " + tags[idx]);
			}
		}

		// Write new constant pool
		output.copyFrom(content, 0, 10);
		int constantPoolSize = pool.writeTo(output);
		output.overwriteShort(CONSTANT_POOL_SIZE_INDEX, constantPoolSize);

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
	private @NotNull MappedClass mapClass(short classPoolIndex) {
		short index = readShort(startIndices[classPoolIndex] + 1);
		dbgAssert(tags[classPoolIndex] == CONSTANT_CLASS);

		if (classCache[index] != null)
			return classCache[index];

		String name = readUtf8FromPool(index);
		MappedClass mappedClass = LowLevelMapper.classes.get(name, SOURCE_MAPPING);
		if (mappedClass == null)
			// Not mapped
			return classCache[index] = UNMAPPED;

		classCache[index] = mappedClass;
		return mappedClass;
	}

	/**
	 * Maps a CONSTANT_NameAndType_info entry and inserts it into the new constant pool.
	 *
	 * @param classPoolIndex       Constant pool index of CONSTANT_Class_info entry.
	 * @param nameAndTypePoolIndex Constant pool index of CONSTANT_NameAndType_info entry.
	 * @param isField              true if the CONSTANT_NameAndType_info references a field, false otherwise.
	 * @return The position of the mapped entry.
	 */
	private short mapAndInsertNameAndType(short classPoolIndex, short nameAndTypePoolIndex, boolean isField) {
		dbgAssert(tags[classPoolIndex] == CONSTANT_CLASS);
		dbgAssert(tags[nameAndTypePoolIndex] == CONSTANT_NAME_AND_TYPE);

		MappedClass mappedClass = mapClass(classPoolIndex);
		int nameAndTypeIndex = startIndices[nameAndTypePoolIndex];
		int nameIndex = nameAndTypeIndex + 1; // position of namePoolIndex in content
		int descIndex = nameAndTypeIndex + 3; // position of descPoolIndex in content
		short namePoolIndex = readShort(nameIndex); // position of name entry in constant pool
		short descPoolIndex = readShort(descIndex); // position of descriptor entry in constant pool

		String name = readUtf8FromPool(namePoolIndex);
		String desc = readUtf8FromPool(descPoolIndex);
		short newNamePoolIndex, newDescPoolIndex;

		if (mappedClass == UNMAPPED) {
			newNamePoolIndex = pool.insertUtf8Cached(name, namePoolIndex, NAME_CACHE);
			newDescPoolIndex = pool.insertUtf8CachedDesc(desc, isField, descPoolIndex);
		} else {
			MappedMember member = null;
			if (isField)
				member = mappedClass.getFieldRecursive(name, SOURCE_MAPPING);
			else {
				if (!name.equals("<init>"))
					member = mappedClass.getMethodRecursive(name + desc, SOURCE_MAPPING);
			}

			if (member == null) {
				newNamePoolIndex = pool.insertUtf8Cached(name, namePoolIndex, NAME_CACHE);
				newDescPoolIndex = pool.insertUtf8CachedDesc(desc, isField, descPoolIndex);
			} else {
				newNamePoolIndex = pool.insertUtf8(member.getName(TARGET_MAPPING));
				newDescPoolIndex = pool.insertUtf8Cached(member.getDesc(TARGET_MAPPING), descPoolIndex, DESCRIPTOR_CACHE);
			}
		}

		return pool.insertNameAndType(newNamePoolIndex, newDescPoolIndex);
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
			case "ConstantValue", "SourceFile", "LineNumberTable", "StackMapTable", "Exceptions" -> {
				// Doesn't contain anything that must be mapped
			}
			case "BootstrapMethods" -> {
				// No need to map as Minecraft does not contain any bootstrap methods, so all entries are unobfuscated
			}
			case "Signature" -> {
				short signatureIndex = readShort(cursor + 6);
				pool.freeUtf8(signatureIndex);
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
					pool.freeUtf8(descriptorIndex);

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
					pool.freeUtf8(signatureIndex);

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
						pool.freeUtf8(innerNameIndex);
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
				short nameAndTypePoolIndex = readShort(cursor + 8);
				if (nameAndTypePoolIndex != 0) {
					short newNameAndTypePoolIndex = mapAndInsertNameAndType(classIndex, nameAndTypePoolIndex, false);
					overwriteAbs(cursor + 8, newNameAndTypePoolIndex);
				}
			}
			default -> {
				if (UNRECOGNIZED_ATTRIBUTES.add(attrName))
					System.err.println("Unimplemented attribute " + attrName);
			}
		}

		return attributeLength + 6;
	}

	private short readShort(int cursor) {
		return (short) (((content[cursor] & 0xFF) << 8) | (content[cursor + 1] & 0xFF));
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
	private @NotNull String readUtf8FromPool(int index) {
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
