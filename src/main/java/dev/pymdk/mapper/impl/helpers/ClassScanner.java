package dev.pymdk.mapper.impl.helpers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static dev.pymdk.mapper.impl.LowLevelMapper.dbgAssert;

/**
 * Collects the references classes by reading the constant pool.
 */
public class ClassScanner implements Constants {

	/**
	 * Collects the references classes by reading the constant pool.
	 */
	public static List<String> getClassReferences(byte[] content) {
		List<Short> classes = new ArrayList<>();

		int rawPoolCount = readShort(content, CONSTANT_POOL_SIZE_OFFSET) & 0xFFFF;
		if (rawPoolCount > Short.MAX_VALUE)
			throw new UnsupportedOperationException("Constant pools with more than " + Short.MAX_VALUE + " entries are not supported");

		short poolCount = (short) rawPoolCount;
		int[] startIndices = new int[poolCount + 1];
		byte[] tags = new byte[poolCount];

		// Decode content pool
		int cursor = CONSTANT_POOL_SIZE_OFFSET + 2;
		for (short idx = 1; idx < poolCount; idx++) {
			startIndices[idx] = cursor;
			byte tag = content[cursor++];
			tags[idx] = tag;
			switch (tag) {
				case CONSTANT_UTF8 -> {
					short length = (short) (((content[cursor++] & 0xFF) << 8) | (content[cursor++] & 0xFF));
					cursor += length;
				}
				case CONSTANT_CLASS -> {
					cursor += 2;
					classes.add(idx);
				}
				case CONSTANT_STRING, CONSTANT_METHOD_TYPE, CONSTANT_MODULE, CONSTANT_PACKAGE -> cursor += 2;
				case CONSTANT_METHOD_HANDLE -> cursor += 3;
				case CONSTANT_NAME_AND_TYPE, CONSTANT_INTEGER, CONSTANT_FLOAT, CONSTANT_FIELDREF, CONSTANT_METHODREF,
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

		return classes.stream()
				.map(s -> readClass(content, startIndices, tags, s))
				.collect(Collectors.toList());
	}

	private static String readClass(byte[] content, int[] startIndices, byte[] tags, short classIndex) {
		dbgAssert(tags[classIndex] == CONSTANT_CLASS);
		short nameIndex = readShort(content, startIndices[classIndex] + 1);

		//noinspection DuplicatedCode
		dbgAssert(tags[nameIndex] == CONSTANT_UTF8);

		int cursor = startIndices[nameIndex] + 1;
		short length = (short) (((content[cursor++] & 0xFF) << 8) | (content[cursor++] & 0xFF));
		return new String(content, cursor, length, StandardCharsets.UTF_8);
	}

	private static short readShort(byte[] content, int cursor) {
		return (short) (((content[cursor] & 0xFF) << 8) | (content[cursor + 1] & 0xFF));
	}

}
