package dev.pymdk.mapper.impl.helpers;

import dev.pymdk.mapper.impl.helpers.ConstantPool.PoolEntry;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;

import static dev.pymdk.mapper.impl.LowLevelMapper.dbgAssert;

/**
 * A {@link ConstantPool} view that overwrites claimed but unused pool entries.
 */
public class ReusePool<T> {

	/**
	 * The destination pool.
	 */
	private final ConstantPool pool;

	/**
	 * Value -> pool index
	 */
	private final HashMap<T, Short> cache;

	/**
	 * List of unused indices.
	 */
	private final short[] freeIndices;
	private int freeCursor = 0;
	private int claimCursor = 0;

	/**
	 * index -> was marked.
	 * Prevents freeing the same index multiple times.
	 */
	private final boolean[] unused;

	ReusePool(ConstantPool pool, int poolCount) {
		this.pool = pool;
		this.cache = new HashMap<>(poolCount);
		this.freeIndices = new short[poolCount];
		this.unused = new boolean[poolCount];
	}

	/**
	 * Marks a claimed pool entry as free.
	 */
	public void free(short index) {
		if (unused[index])
			return;

		freeIndices[freeCursor++] = index;
		unused[index] = true;
	}

	/**
	 * Writes into the first free pool entry.
	 * If no entry is free, the value is appended to the {@link ConstantPool#extendedPool}.
	 */
	public short insert(@NotNull T value, @NotNull PoolEntry entry) {
		if (claimCursor >= freeCursor) {
			int index = pool.pool.length + pool.extendedPool.size();
			pool.extendedPool.add(entry);
			dbgAssert((short) index == index);
			return (short) index;
		}

		short index = freeIndices[claimCursor];
		dbgAssert(index != 0);

		Short prevIndex = cache.putIfAbsent(value, index);
		if (prevIndex != null)
			return prevIndex;

		dbgAssert(pool.pool[index] == ConstantPool.DBG_UNSET || pool.pool[index] instanceof ConstantPool.UnsetCopyEntry);
		pool.pool[index] = entry;
		claimCursor++;
		return index;
	}

}
