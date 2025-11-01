package dev.pymdk.mapper;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {

	/**
	 * Usage: java -jar mapper.jar &lt;input-file&gt; &lt;mapping-file&gt; &lt;output-file&gt;
	 */
	public static void main(String[] args) throws IOException {
		String inputFile = args[0];
		String mappings = args[1];
		String outputFile = args[2];

		Mapper mapper = new Mapper(MappingData.read(Path.of(mappings)));

		try (
				JarFile jarFile = new JarFile(new File(inputFile));
				ZipOutputStream output = new ZipOutputStream(new FileOutputStream(outputFile))
		) {
			Enumeration<JarEntry> entries = jarFile.entries();

			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();

				// Skip non-class files
				if (!entry.getName().endsWith(".class"))
					continue;

				output.putNextEntry(new ZipEntry(entry.getName()));

				// Read class
				ClassNode classNode = new ClassNode();
				new ClassReader(jarFile.getInputStream(entry)).accept(classNode, 0);

				// Process class
				mapper.map(classNode);

				// Write class
				ClassWriter classWriter = new ClassWriter(0);
				classNode.accept(classWriter);

				output.write(classWriter.toByteArray());
			}

			output.closeEntry();
			output.finish();
		}
	}

}