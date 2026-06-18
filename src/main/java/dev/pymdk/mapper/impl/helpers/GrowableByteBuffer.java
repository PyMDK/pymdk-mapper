package dev.pymdk.mapper.impl.helpers;

import dev.pymdk.mapper.FastMapper;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static dev.pymdk.mapper.impl.LowLevelMapper.dbgAssert;

/**
 * An automatically growing byte[] wrapper.
 */
@SuppressWarnings("ConstantValue")
public class GrowableByteBuffer implements Constants, FastMapper.Output {

	private byte[] data;
	public int cursor = 0;

	public GrowableByteBuffer(int capacity) {
		this.data = new byte[capacity];
	}

	/**
	 * Writes a CONSTANT_Utf8_info entry to the current position.
	 */
	public void writeUtf8Entry(@NotNull String text) {
		dbgAssert(text != null);

		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		short length = (short) data.length;
		ensureFree(3 + data.length);
		this.data[cursor++] = CONSTANT_UTF8;
		this.data[cursor++] = (byte) (length >>> 8);
		this.data[cursor++] = (byte) (length & 0xFF);
		copyFrom(data, 0, data.length);
	}

	/**
	 * Writes a CONSTANT_Class_info entry to the current position.
	 */
	public void writeClassEntry(short nameIndex) {
		ensureFree(3);
		this.data[cursor++] = CONSTANT_CLASS;
		this.data[cursor++] = (byte) (nameIndex >>> 8);
		this.data[cursor++] = (byte) (nameIndex & 0xFF);
	}

	/**
	 * Writes a CONSTANT_NameAndType_info entry to the current position.
	 */
	public void writeNameAndTypeEntry(short nameIndex, short descriptorIndex) {
		writeRefEntry(CONSTANT_NAME_AND_TYPE, nameIndex, descriptorIndex);
	}

	/**
	 * Writes a CONSTANT_NameAndType_info, CONSTANT_Fieldref_info, CONSTANT_Methodref_info
	 * or CONSTANT_InterfaceMethodref_info entry to the current position.
	 */
	public void writeRefEntry(byte type, short classIndex, short nameAndTypeIndex) {
		ensureFree(5);
		this.data[cursor++] = type;
		this.data[cursor++] = (byte) (classIndex >>> 8);
		this.data[cursor++] = (byte) (classIndex & 0xFF);
		this.data[cursor++] = (byte) (nameAndTypeIndex >>> 8);
		this.data[cursor++] = (byte) (nameAndTypeIndex & 0xFF);
	}

	/**
	 * Writes a short to the given index, ignoring the current cursor position.
	 */
	public void overwriteShort(int index, int count) {
		dbgAssert((short) count == count);
		data[index] = (byte) (count >> 8 & 0xFF);
		data[index + 1] = (byte) (count & 0xFF);
	}

	/**
	 * Copies a blob to the given index, ignoring the current cursor position.
	 */
	public void copyFrom(byte[] source, int index, int length) {
		ensureSize(cursor + length);
		System.arraycopy(source, index, data, cursor, length);
		cursor += length;
	}

	private void ensureFree(int freeCapacity) {
		ensureSize(cursor + freeCapacity);
	}

	private void ensureSize(int size) {
		if (data.length < size) {
			int newCapacity = data.length;
			do {
				newCapacity = newCapacity + (newCapacity >> 1);
			} while (newCapacity < size);
			data = Arrays.copyOf(data, newCapacity);
		}
	}

	@Override
	public byte[] getExtendedData() {
		return data;
	}

	@Override
	public int getSize() {
		return cursor;
	}
}
