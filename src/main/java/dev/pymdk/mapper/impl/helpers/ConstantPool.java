package dev.pymdk.mapper.impl.helpers;

import dev.pymdk.mapper.impl.LowLevelMapper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.pymdk.mapper.impl.LowLevelMapper.dbgAssert;
import static dev.pymdk.mapper.impl.helpers.ConstantPool.Utf8CacheType.DESCRIPTOR_CACHE;

/**
 * A constant pool writer.
 */
@SuppressWarnings("ConstantValue")
public class ConstantPool {

	/**
	 * Synthetic entry to fill and unused Utf8 / NameAndType entries.
	 * Removing them would mean reindexing all references to following entries, so adding an empty entry is MUCH faster.
	 */
	static final PoolEntry DBG_UNSET = (out, i) -> out.writeClassEntry((short) 1);

	/**
	 * Pool entry after CONSTANT_Long_info and CONSTANT_Double_info which take 2 slots.
	 */
	static final PoolEntry SKIP = (_, _) -> {};

	protected final PoolEntry[] pool;

	/**
	 * Extended pool for when the pool has no unused entries that can be overwritten.
	 * Note that the final pool might contain unused entries and an extended pool, as UTF8 entries can be pointed to by a forward reference.
	 */
	protected final List<PoolEntry> extendedPool = new ArrayList<>();

	/**
	 * Cursor into pool for linear writes (everything except for ReusePool).
	 */
	private int poolWriteCursor = 1;

	/**
	 * Overwritten CONSTANT_Utf8_info entries.
	 */
	private final ReusePool<String> utf8Entries;

	/**
	 * index of the source CONSTANT_Utf8_info -> packed int.
	 *
	 * @see ConstantPool#insertUtf8Cached
	 * @see Utf8CacheType
	 */
	private final int[] utf8Cache;

	/**
	 * All CONSTANT_NameAndType_info entries, represented by a packed int.
	 */
	private final ReusePool<Integer> nameAndTypeEntries;

	public ConstantPool(short poolCount) {
		pool = new PoolEntry[poolCount];
		Arrays.fill(pool, DBG_UNSET);

		utf8Entries = new ReusePool<>(
				this,
				poolCount);

		nameAndTypeEntries = new ReusePool<>(
				this,
				poolCount);

		utf8Cache = new int[poolCount];
	}

	// ////////////////// //
	// CONSTANT_Utf8_info //
	// ////////////////// //

	public void freeUtf8(short index) {
		utf8Entries.free(index);
	}

	/**
	 * Inserts a CONSTANT_Utf8_info with the given text.
	 *
	 * @return The position of the inserted entry.
	 */
	public short insertUtf8(@NotNull String text) {
		dbgAssert(text != null);
		return utf8Entries.insert(text, new Utf8Entry(text));
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
		short cached = (short) (utf8Cache[sourceIndex] >>> shift & 0xFFFF);
		if (cached != 0)
			return cached;

		short inserted = utf8Entries.insert(value, new Utf8Entry(value));
		utf8Cache[sourceIndex] |= (inserted & 0xFFFF) << shift;
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
		short cached = (short) (utf8Cache[sourceIndex] >>> shift & 0xFFFF);
		if (cached != 0)
			return cached;

		String value = LowLevelMapper.mapStandaloneDesc(descriptor, isField);
		short inserted = utf8Entries.insert(value, new Utf8Entry(value));
		utf8Cache[sourceIndex] |= (inserted & 0xFFFF) << shift;
		return inserted;
	}

	// ///////////////////////// //
	// CONSTANT_NameAndType_info //
	// ///////////////////////// //

	public void freeNameAndType(short index) {
		nameAndTypeEntries.free(index);
	}

	/**
	 * Inserts a CONSTANT_NameAndType_info with the given data.
	 *
	 * @return The position of the inserted entry.
	 */
	public short insertNameAndType(short nameIndex, short descIndex) {
		int value = nameIndex << 16 | descIndex;
		return nameAndTypeEntries.insert(value, new NameAndTypeEntry(nameIndex, descIndex));
	}

	// ///// //
	// other //
	// ///// //

	/**
	 * Inserts a CONSTANT_Class_info with the given data at the current cursor position.
	 */
	public void addClassEntry(short nameIndex) {
		dbgAssert(pool[poolWriteCursor] == DBG_UNSET);
		pool[poolWriteCursor++] = new ClassEntry(nameIndex);
	}

	/**
	 * Inserts a CONSTANT_Fieldref_info, CONSTANT_Methodref_info or
	 * CONSTANT_InterfaceMethodref_info with the given data at the current cursor position.
	 */
	public void addRefEntry(byte tag, short classIndex, short nameAndTypeIndex) {
		dbgAssert(pool[poolWriteCursor] == DBG_UNSET);
		pool[poolWriteCursor++] = new RefEntry(tag, classIndex, nameAndTypeIndex);
	}

	/**
	 * Copies a CONSTANT_Long_info or CONSTANT_Double_info with the given data at the current cursor position.
	 */
	public void doubleCopyFrom(byte[] source, int index, int length) {
		dbgAssert(pool[poolWriteCursor] == DBG_UNSET);
		dbgAssert(pool[poolWriteCursor + 1] == DBG_UNSET);
		copyFrom(source, index, length);
		pool[poolWriteCursor++] = SKIP;
	}

	/**
	 * Copies an arbitrary constant pool entry with the given data at the current cursor position.
	 */
	public void copyFrom(byte[] source, int index, int length) {
		dbgAssert(pool[poolWriteCursor] == DBG_UNSET);
		pool[poolWriteCursor++] = new CopyEntry(source, index, length);
	}

	/**
	 * Copies an arbitrary constant pool entry with the given data at the current cursor position, if it
	 * does not already contain a value (from {@link ReusePool}).
	 */
	public void copyIfUnset(byte[] source, int index, int length) {
		if (pool[poolWriteCursor] == DBG_UNSET)
			pool[poolWriteCursor] = new UnsetCopyEntry(source, index, length);

		poolWriteCursor++;
	}

	/**
	 * Serializes the constant pool and writes it.
	 * @return The final constant pool.
	 */
	public int writeTo(@NotNull GrowableByteBuffer out) {
		for (int i = 1, poolLength = poolWriteCursor; i < poolLength; i++)
			pool[i].writeTo(out, i);

		for (PoolEntry poolEntry : extendedPool)
			poolEntry.writeTo(out, poolWriteCursor++);

		return poolWriteCursor;
	}

	public interface PoolEntry {
		void writeTo(@NotNull GrowableByteBuffer out, int index);
	}

	/**
	 * A CONSTANT_Utf8_info entry.
	 */
	record Utf8Entry(String value) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out, int index) {
			out.writeUtf8Entry(value);
		}
	}

	/**
	 * A CONSTANT_Class_info entry.
	 */
	record ClassEntry(short nameIndex) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out, int index) {
			out.writeClassEntry(nameIndex);
		}
	}

	/**
	 * A CONSTANT_NameAndType_info entry.
	 */
	record NameAndTypeEntry(short nameIndex, short descIndex) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out, int index) {
			out.writeNameAndTypeEntry(nameIndex, descIndex);
		}
	}

	/**
	 * A CONSTANT_Fieldref_info, CONSTANT_Methodref_info or CONSTANT_InterfaceMethodref_info entry.
	 */
	record RefEntry(byte tag, short classIndex, short nameAndTypeIndex) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out, int index) {
			out.writeRefEntry(tag, classIndex, nameAndTypeIndex);
		}
	}

	/**
	 * An arbitrary constant pool entry.
	 */
	record CopyEntry(byte[] source, int index, int length) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out, int index) {
			out.copyFrom(source, this.index, length);
		}
	}

	/**
	 * An arbitrary constant pool entry that might be overwritten later.
	 * The same as {@link CopyEntry}, just as a marker for sanity checks.
	 */
	public record UnsetCopyEntry(byte[] source, int index, int length) implements PoolEntry {
		@Override
		public void writeTo(@NotNull GrowableByteBuffer out, int index) {
			out.copyFrom(source, this.index, length);
		}
	}

	/**
	 * The position of the cached value in the packed int.
	 */
	public enum Utf8CacheType {
		NAME_CACHE,
		DESCRIPTOR_CACHE;

		private final int shift = ordinal() * 16;
	}

}
