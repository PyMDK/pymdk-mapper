import dev.pymdk.mapper.FastMapper;
import dev.pymdk.mapper.Mapping;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Maps the whole 1.8.9 client jar.
 */
public class Full189Test {

	static List<byte[]> data = new ArrayList<>();

	@BeforeAll
	public static void init() throws IOException {
		var compressedMappings = Path.of("run/mappings-1.8.9-mcp.json.xz");
		var rawMappings = Path.of("run/mappings-1.8.9-mcp.json");
		var minecraft = Path.of("run/client-1.8.9.jar");

		// Fetch mappings
		Util.fetch("https://maven.pymdk.dev/dev/pymdk/mappings/1.8.9/mappings-1.8.9-mcp.json.xz",
				compressedMappings, "zK8ztZ0UxhKr/Kmw8mWR8FrIrxO1LkmxdM+annA411U=");
		if (Files.notExists(rawMappings) || Util.checkHashFail(rawMappings, "JMqZhVdaZJrghxBHoriSMSwmGRU6z3U+QufZlid+8FM="))
			Util.decompress(compressedMappings, rawMappings);

		// Fetch 1.8.9 client jar
		Util.fetch("https://piston-data.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar",
				minecraft, "FPDZbRpW+09cOyIz0AaZUliT/lzj3PGB595ZEgWV0pg=");

		FastMapper.configure(Mapping.OBFUSCATED, Mapping.UNOBFUSCATED);
		FastMapper.loadMappings(rawMappings);

		try (InputStream inputStream = Files.newInputStream(minecraft)) {
			try (ZipInputStream zipIn = new ZipInputStream(inputStream)) {
				ZipEntry entry;
				while ((entry = zipIn.getNextEntry()) != null) {
					if (entry.getName().endsWith(".class")) {
						byte[] content = zipIn.readAllBytes();
						data.add(content);
					}
				}
			}
		}
	}

	@Test
	public void map189() {
		for (byte[] content : data)
			FastMapper.mapClass(content);
	}

}
