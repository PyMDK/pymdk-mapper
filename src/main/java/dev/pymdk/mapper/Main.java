package dev.pymdk.mapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Main {

	public static void main(String[] args) throws IOException {
		if (args.length != 3) {
			System.err.println("Usage: java -jar pymdk-mapper-2.0.0.jar <input-file> <mapping-file> <output-file>");
			System.exit(1);
		}

		FastMapper.configure(Mapping.OBFUSCATED, Mapping.UNOBFUSCATED);
		FastMapper.loadMappings(Path.of(args[1]));

		try (var zin = new ZipInputStream(Files.newInputStream(Path.of(args[0])));
		     var zout = new ZipOutputStream(Files.newOutputStream(Path.of(args[2])))) {

			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				zout.putNextEntry(new ZipEntry(entry.getName()));

				if (entry.getName().endsWith(".class"))
					FastMapper.mapClass(zin.readAllBytes()).writeTo(zout);
				else
					zin.transferTo(zout);

				zout.closeEntry();
			}
		}
	}

}
