package dev.pymdk.mapper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.pymdk.mapper.impl.ClassMapper;
import dev.pymdk.mapper.impl.LowLevelMapper;
import dev.pymdk.mapper.impl.MappingEntries;
import dev.pymdk.mapper.impl.helpers.GrowableByteBuffer;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Fast class file mapper.
 */
public class FastMapper {

	public static void configure(@NotNull Mapping sourceMapping, @NotNull Mapping targetMapping) {
		LowLevelMapper.SOURCE_MAPPING = sourceMapping;
		LowLevelMapper.TARGET_MAPPING = targetMapping;
	}

	/**
	 * Loads mappings in PyMDK format from a file.
	 */
	public static void loadMappings(@NotNull Path path) throws IOException {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			Collection<MappingEntries.MappedClass> mappedClasses = new Gson().fromJson(reader, new TypeToken<Collection<MappingEntries.MappedClass>>() {}.getType());
			LowLevelMapper.classes.addAll(mappedClasses);
			LowLevelMapper.classes.create();
		}
	}

	/**
	 * Applies mappings to a class file.
	 *
	 * @param input The ClassFile structure, which will be mutated in-place.
	 */
	public static @NotNull Output mapClass(byte[] input) {
		GrowableByteBuffer output = new GrowableByteBuffer(input.length);
		new ClassMapper(input, output).map();
		return output;
	}

	/**
	 * The emitted class file bytes.
	 */
	public interface Output {

		/**
		 * @return The written data.
		 */
		default byte[] getData() {
			byte[] data = getExtendedData();
			int size = getSize();
			byte[] trimmed = new byte[size];
			System.arraycopy(data, 0, trimmed, 0, size);
			return trimmed;
		}

		/**
		 * Writes the output to a OutputStream.
		 */
		default void writeTo(@NotNull OutputStream out) throws IOException {
			out.write(getExtendedData(), 0, getSize());
		}

		/**
		 * @return the underlying, untrimmed byte array. Usually bigger than {@link #getSize()} due to its
		 * growth factor.
		 */
		byte[] getExtendedData();

		/**
		 * The size of written data within {@link #getExtendedData()}.
		 */
		int getSize();

	}
}
