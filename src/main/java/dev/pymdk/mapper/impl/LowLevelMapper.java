package dev.pymdk.mapper.impl;

import dev.pymdk.mapper.Mapping;
import dev.pymdk.mapper.impl.MappingEntries.MappedClass;
import dev.pymdk.mapper.impl.MappingEntries.MappedList;
import org.jetbrains.annotations.NotNull;

/**
 * Helper methods and configuration.
 */
public class LowLevelMapper {

	public static Mapping SOURCE_MAPPING;
	public static Mapping TARGET_MAPPING;

	public static final MappedList<MappedClass> classes = new MappedList<>();

	/**
	 * Maps a descriptor.
	 */
	public static @NotNull String mapStandaloneDesc(@NotNull String desc, boolean isField) {
		return isField
				? mapStandaloneFieldDesc(desc)
				: mapStandaloneMethodDesc(desc);
	}

	/**
	 * Maps a field (or return) descriptor.
	 */
	public static @NotNull String mapStandaloneFieldDesc(@NotNull String desc) {
		if (desc.charAt(0) == '[')
			return "[" + mapStandaloneFieldDesc(desc.substring(1));
		if (desc.charAt(0) != 'L') {
			if (desc.length() != 1)
				throw new UnsupportedOperationException(desc);
			return desc;
		}

		String name = desc.substring(1, desc.length() - 1);
		MappedClass mappedClass = classes.get(name, SOURCE_MAPPING);
		if (mappedClass == null)
			return desc;

		return "L" + mappedClass.getName(TARGET_MAPPING) + ";";
	}

	/**
	 * Maps a method descriptor.
	 */
	public static @NotNull String mapStandaloneMethodDesc(@NotNull String desc) {
		StringBuilder builder = new StringBuilder(desc.length());
		builder.append('(');
		char[] chars = desc.toCharArray();
		for (int index = 1; index < desc.length(); index++) {
			if (chars[index] != 'L') {
				builder.append(chars[index]);
				continue;
			}

			int endIndex = index;
			while (chars[endIndex] != ';') endIndex++;

			String name = desc.substring(index, endIndex + 1);
			builder.append(mapStandaloneFieldDesc(name));
			index = endIndex;
		}

		return builder.toString();
	}

	/**
	 * Debug assertions.
	 * These should never fail for valid class files.
	 */
	public static void dbgAssert(boolean value) {
		if (!value)
			throw new IllegalStateException("Invalid class file.");
	}

}
