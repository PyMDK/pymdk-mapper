package dev.pymdk.mapper.impl.helpers;

import dev.pymdk.mapper.impl.LowLevelMapper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.pymdk.mapper.impl.LowLevelMapper.dbgAssert;
import static dev.pymdk.mapper.impl.helpers.ConstantPool.Utf8CacheType.DESCRIPTOR_CACHE;

/**
 * A constant pool writer.
 */
@SuppressWarnings("ConstantValue")
public class ConstantPool {

	/**
	 * The size of the original pool.
	 */
	private final short extendedPoolStart;

	protected final List<PoolEntry> extendedPool;

	/**
	 * Index of the source CONSTANT_Utf8_info -> packed (mapped name, mapped desc).
	 *
	 * @see ConstantPool#insertUtf8Cached
	 * @see Utf8CacheType
	 */
	private final int[] mappedUTF8;

	/**
	 * Map of text to pool index (original and extended).
	 */
	private final Map<String, Short> utf8Cache;

	/**
	 * Map of packed (nameIdx, descIdx) to pool index.
	 */
	private final Map<Integer, Short> nameAndTypeCache;

	public ConstantPool(short poolCount) {
		extendedPoolStart = poolCount;
		extendedPool = new ArrayList<>(poolCount / 2);
		mappedUTF8 = new int[poolCount];
		utf8Cache = new HashMap<>(poolCount / 2);
		nameAndTypeCache = new HashMap<>(poolCount / 4);
	}

	// ////////////////// //
	// CONSTANT_Utf8_info //
	// ////////////////// //

	public void cacheUtf8(@NotNull String text, short index) {
		utf8Cache.put(text, index);
	}

	/**
	 * Inserts a CONSTANT_Utf8_info with the given text.
	 *
	 * @return The position of the inserted entry.
	 */
	public short insertUtf8(@NotNull String text) {
		dbgAssert(text != null);
		Short index = utf8Cache.get(text);
		if (index != null)
			return index;

		int newIndex = extendedPool.size() + extendedPoolStart;
		if (newIndex > 0xFFFF)
			throw new UnsupportedOperationException("Constant pool is too big");

		utf8Cache.put(text, (short) newIndex);
		extendedPool.add(new Utf8Entry(text));
		return (short) newIndex;
	}

	/**
	 * Inserts a CONSTANT_Utf8_info with array-backed deduplication.
	 *
	 * @param value       The new Utf8_info contents.
	 * @param sourceIndex The source of the data (before any mappings were applied).
	 * @param cacheType   The cache to be used.
	 * @return The position of the inserted entry.
	 */
	public short insertUtf8Cached(@NotNull String value, short sourceIndex, @NotNull Utf8CacheType cacheType) {
		dbgAssert(value != null);
		int shift = cacheType.shift;
		short cached = (short) (mappedUTF8[sourceIndex] >>> shift & 0xFFFF);
		if (cached != 0)
			return cached;

		short inserted = insertUtf8(value);
		mappedUTF8[sourceIndex] |= (inserted & 0xFFFF) << shift;
		return inserted;
	}

	/**
	 * Maps and inserts a descriptor as a CONSTANT_Utf8_info with array-backed deduplication.
	 *
	 * @param descriptor  The unmapped descriptor.
	 * @param isField     true if the descriptor is descriptor, false otherwise.
	 * @param sourceIndex The source of the descriptor.
	 * @return The position of the inserted entry.
	 */
	public short insertUtf8CachedDesc(@NotNull String descriptor, boolean isField, short sourceIndex) {
		int shift = DESCRIPTOR_CACHE.shift;
		short cached = (short) (mappedUTF8[sourceIndex] >>> shift & 0xFFFF);
		if (cached != 0)
			return cached;

		String value = LowLevelMapper.mapStandaloneDesc(descriptor, isField);
		short inserted = insertUtf8(value);
		mappedUTF8[sourceIndex] |= (inserted & 0xFFFF) << shift;
		return inserted;
	}

	// ///////////////////////// //
	// CONSTANT_NameAndType_info //
	// ///////////////////////// //

	public void cacheNameAndType(short nameIndex, short descIndex, short index) {
		int key = nameIndex << 16 | descIndex;
		nameAndTypeCache.put(key, index);
	}

	/**
	 * Inserts a CONSTANT_NameAndType_info with the given data.
	 *
	 * @return The position of the inserted entry.
	 */
	public short insertNameAndType(short nameIndex, short descIndex) {
		int key = nameIndex << 16 | descIndex;
		Short index = nameAndTypeCache.get(key);
		if (index != null)
			return index;

		int newIndex = extendedPool.size() + extendedPoolStart;
		if (newIndex > 0xFFFF)
			throw new UnsupportedOperationException("Constant pool is too big");

		nameAndTypeCache.put(key, (short) newIndex);
		extendedPool.add(new NameAndTypeEntry(nameIndex, descIndex));
		return (short) newIndex;
	}

	// ///// //
	// other //
	// ///// //

	/**
	 * Serializes the constant pool and writes it.
	 *
	 * @return The final constant pool.
	 */
	public int writeTo(@NotNull GrowableByteBuffer out) {
		for (PoolEntry poolEntry : extendedPool)
			poolEntry.writeTo(out);

		return extendedPool.size() + extendedPoolStart;
	}

	public interface PoolEntry {
		void writeTo(@NotNull GrowableByteBuffer out);
	}

	/**
	 * A CONSTANT_Utf8_info entry.
	 */
	record Utf8Entry(String value) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out) {
			out.writeUtf8Entry(value);
		}
	}

	/**
	 * A CONSTANT_NameAndType_info entry.
	 */
	record NameAndTypeEntry(short nameIndex, short descIndex) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out) {
			out.writeNameAndTypeEntry(nameIndex, descIndex);
		}
	}

	/**
	 * The position of the cached value in the packed int.
	 */
	public enum Utf8CacheType {
		/**
		 * Member names without a mapping.
		 */
		NAME_CACHE,

		/**
		 * Mapped member descriptors.
		 */
		DESCRIPTOR_CACHE;

		private final int shift = ordinal() * 16;
	}

}
