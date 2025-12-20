package dev.pymdk.mapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static dev.pymdk.mapper.Main.readStr;
import static java.nio.ByteOrder.BIG_ENDIAN;

public record AugmentationData(HashMap<String, ClassAugmentation> classes) {

	private static void skipString(ByteBuffer buf) {
		int length = buf.getInt();
		buf.position(buf.position() + length);
	}

	private static void skipStringList(ByteBuffer buf) {
		for (int i = 0, len = buf.getInt(); i < len; i++)
			skipString(buf);
	}

	private static void skipStringMap(ByteBuffer buf) {
		for (int i = 0, len = buf.getInt(); i < len; i++) {
			skipString(buf);
			skipStringList(buf);
		}
	}

	/**
	 * Reads a mapping augmentations file in PyMDK binary v1 format.
	 */
	public static AugmentationData read(Path path) throws IOException {
		byte[] data = Files.readAllBytes(path);
		ByteBuffer buf = ByteBuffer.wrap(data).order(BIG_ENDIAN);

		skipStringMap(buf); // packages

		HashMap<String, ClassAugmentation> classes = new HashMap<>();
		for (int i = 0, classAmount = buf.getInt(); i < classAmount; i++) {
			String name = readStr(buf);

			skipStringList(buf); // javadoc
			skipStringMap(buf); // fields

			HashMap<MappingData.Method, MethodAugmentation> methods = new HashMap<>();
			for (int j = 0, methodAmount = buf.getInt(); j < methodAmount; j++) {
				MappingData.Method method = new MappingData.Method(readStr(buf), readStr(buf));

				HashMap<Short, String> parameters = new HashMap<>();
				skipStringList(buf); // javadoc
				for (int k = 0, parameterAmount = buf.getInt(); k < parameterAmount; k++) {
					parameters.put((short) buf.getChar(), readStr(buf));

					if (buf.get() == 1)
						skipString(buf); // javadoc
				}

				methods.put(method, new MethodAugmentation(parameters));
			}

			classes.put(name,new ClassAugmentation(methods));
		}

		return new AugmentationData(classes);
	}

	public record ClassAugmentation(HashMap<MappingData.Method, MethodAugmentation> methods) {}

	/**
	 * @param parameters index -> name
	 */
	public record MethodAugmentation(HashMap<Short, String> parameters) {}

}
