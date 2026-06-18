import org.tukaani.xz.XZInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class Util {

	/**
	 * @param hash base64-encoded SHA256
	 */
	public static void fetch(String url, Path filePath, String hash) throws IOException {
		if (!Files.exists(filePath) || checkHashFail(filePath, hash)) {
			if (filePath.getParent() != null)
				Files.createDirectories(filePath.getParent());
			URLConnection c = URI.create(url).toURL().openConnection();
			c.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36");
			Files.copy(c.getInputStream(), filePath, REPLACE_EXISTING);

			if (checkHashFail(filePath, hash))
				throw new IOException("File " + filePath + " has an invalid hash!");
		}
	}

	/**
	 * @param targetHash base64-encoded SHA256
	 */
	public static boolean checkHashFail(Path libPath, String targetHash) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] fileHash = md.digest(Files.readAllBytes(libPath));
			return !Arrays.equals(fileHash, Base64.getDecoder().decode(targetHash));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Decompresses a XZ file.
	 */
	public static void decompress(Path inputPath, Path outputPath) throws IOException {
		try (InputStream fileIn = Files.newInputStream(inputPath);
		     XZInputStream xzIn = new XZInputStream(fileIn);
		     OutputStream fileOut = Files.newOutputStream(outputPath)) {

			byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = xzIn.read(buffer)) != -1) {
				fileOut.write(buffer, 0, bytesRead);
			}
		}
	}

}
