import com.airgaprx.core.kb.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
public class Probe {
  public static void main(String[] args) throws Exception {
    var keys = KeyPairGenerator.getInstance("EC"); keys.initialize(256);
    var verifier = new KbVerifier(keys.generateKeyPair().getPublic().getEncoded());
    var installer = new KbInstaller(Files.createTempDirectory("airgaprx-review-kb-").toFile(), verifier, new KbPolicy(false));
    var payload = new byte[4096]; new Random(1).nextBytes(payload);
    var out = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry(KbPackage.DB)); zip.write(payload); zip.closeEntry(); }
    var truncated = Arrays.copyOf(out.toByteArray(), out.size()/2);
    try { System.out.println("RESULT=" + installer.install(new ByteArrayInputStream(truncated), false)); }
    catch (Exception e) { System.out.println("THREW=" + e.getClass().getName() + ": " + e.getMessage()); }
  }
}