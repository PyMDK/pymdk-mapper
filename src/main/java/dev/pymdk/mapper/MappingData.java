package dev.pymdk.mapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static dev.pymdk.mapper.Main.readStr;
import static java.nio.ByteOrder.BIG_ENDIAN;

/**
 * @param classes src name -> target name
 */
public record MappingData(HashMap<String, ClassMapping> classes) {

	/**
	 * Reads a Minecraft mappings file in PyMDK binary v1 format.
	 */
	public static MappingData read(Path path) throws IOException {
		byte[] data = Files.readAllBytes(path);
		ByteBuffer buf = ByteBuffer.wrap(data).order(BIG_ENDIAN);

		HashMap<String, ClassMapping> classes = new HashMap<>();

		for (int i = 0, length = buf.getInt(); i < length; i++) {
			String sourceName = readStr(buf);
			String targetName = readStr(buf);

			HashMap<String, String> fields = new HashMap<>();
			for (int j = 0, len = buf.getInt(); j < len; j++)
				fields.put(readStr(buf), readStr(buf));

			HashMap<Method, String> methods = new HashMap<>();
			for (int j = 0, len = buf.getInt(); j < len; j++)
				methods.put(new Method(readStr(buf), readStr(buf)), readStr(buf));

			classes.put(sourceName, new ClassMapping(targetName, fields, methods));
		}

		return new MappingData(classes);
	}

	public ClassMapping getClassMapping(String className) {
		return classes.get(className);
	}

	/**
	 * @param name    target name
	 * @param fields  src name -> target name
	 * @param methods (src name, src desc) -> target name
	 */
	public record ClassMapping(String name,
	                           HashMap<String, String> fields,
	                           HashMap<Method, String> methods) {

		public String mapField(String name) {
			return fields.getOrDefault(name, name);
		}

		public String mapMethod(String name, String desc) {
			return methods.getOrDefault(new Method(name, desc), name);
		}

	}

	public record Method(String name, String descriptor) {}

}
