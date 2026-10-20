import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.SigningCertificateLineage;
import com.android.apksig.util.DataSinks;
import com.android.apksig.util.DataSources;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import javax.security.auth.x500.X500Principal;

/** Official SDK signing operations. Private keys/passwords remain in memory.
 * Arguments and output contain only public certificates, lineage and APK paths. */
public final class AndroidSigning {
    record Key(PrivateKey key, X509Certificate cert) {
        SigningCertificateLineage.SignerConfig lineage() {
            return new SigningCertificateLineage.SignerConfig.Builder(key, cert).build();
        }
        ApkSigner.SignerConfig signer(String name) {
            return new ApkSigner.SignerConfig.Builder(name, key, List.of(cert)).build();
        }
    }
    static String digest(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    static String fingerprint(X509Certificate cert) throws Exception { return digest(cert.getEncoded()); }
    static byte[] environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || value.length() > 65536) throw new IllegalArgumentException();
        return Base64.getMimeDecoder().decode(value);
    }
    static Key load(boolean next) throws Exception {
        var properties = new Properties();
        properties.load(new ByteArrayInputStream(environment(next ? "ANDROID_ROTATION_PROPERTIES" : "LOCAL_PROPERTIES")));
        var store = KeyStore.getInstance("PKCS12");
        char[] password = properties.getProperty("KEYSTORE_PASS").toCharArray();
        char[] keyPassword = properties.getProperty("ALIAS_PASS").toCharArray();
        try {
            store.load(new ByteArrayInputStream(environment(next ? "ANDROID_ROTATION_KEYSTORE_BASE64" : "KEYSTORE_BASE64")), password);
            String alias = properties.getProperty("ALIAS_NAME");
            return new Key((PrivateKey) store.getKey(alias, keyPassword), (X509Certificate) store.getCertificate(alias));
        } finally { java.util.Arrays.fill(password, '\0'); java.util.Arrays.fill(keyPassword, '\0'); }
    }
    static void identity(X509Certificate old, X509Certificate next, String oldPin, String nextPin) throws Exception {
        if (!fingerprint(old).equals(oldPin) || !fingerprint(next).equals(nextPin)
                || !next.getSubjectX500Principal().equals(new X500Principal("CN=Howl"))) {
            throw new IllegalArgumentException();
        }
        next.checkValidity();
    }
    static SigningCertificateLineage checkedLineage(Path file, String oldPin, String nextPin, boolean apk) throws Exception {
        var lineage = apk ? SigningCertificateLineage.readFromApkFile(file.toFile())
                          : SigningCertificateLineage.readFromDataSource(DataSources.asDataSource(ByteBuffer.wrap(Files.readAllBytes(file))));
        var certificates = lineage.getCertificatesInLineage();
        if (certificates.size() != 2) throw new IllegalArgumentException();
        identity(certificates.get(0), certificates.get(1), oldPin, nextPin);
        var capabilities = lineage.getSignerCapabilities(certificates.get(0));
        if (!capabilities.hasInstalledData() || !capabilities.hasPermission() || capabilities.hasSharedUid()
                || capabilities.hasAuth() || capabilities.hasRollback()) throw new IllegalArgumentException();
        return lineage;
    }
    static String verify(Path apk, Path lineagePath, String oldPin, String nextPin) throws Exception {
        var embedded = checkedLineage(apk, oldPin, nextPin, true);
        var serialized = new ByteArrayOutputStream();
        embedded.writeToDataSink(DataSinks.asDataSink(serialized));
        if (!java.util.Arrays.equals(serialized.toByteArray(), Files.readAllBytes(lineagePath)))
            throw new IllegalArgumentException();
        var output = new StringBuilder("{\"selectedSignerByApi\":{");
        for (int api = 24; api <= 36; api++) {
            var result = new ApkVerifier.Builder(apk.toFile()).setMinCheckedPlatformVersion(api)
                .setMaxCheckedPlatformVersion(api).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().size() != 1) throw new IllegalArgumentException();
            String actual = fingerprint(result.getSignerCertificates().get(0));
            if (!actual.equals(api < 33 ? oldPin : nextPin)) throw new IllegalArgumentException();
            if (api >= 33 && !result.isVerifiedUsingV31Scheme()) throw new IllegalArgumentException();
            if (api > 24) output.append(',');
            output.append('"').append(api).append("\":\"").append(actual).append('"');
        }
        return output.append("},\"installedDataTrusted\":true,\"oldKeyRollbackAllowed\":false,\"embeddedLineageMatches\":true,\"newSubject\":\"CN=Howl\"}").toString();
    }
    static void execute(String[] args) throws Exception {
        String action = args[0], oldPin = args[1], nextPin = args[2];
        Path lineagePath = Path.of(args[3]);
        if (action.equals("create-lineage")) {
            Key old = load(false), next = load(true);
            identity(old.cert(), next.cert(), oldPin, nextPin);
            if (!Files.exists(lineagePath)) {
                var capabilities = new SigningCertificateLineage.SignerCapabilities.Builder()
                    .setInstalledData(true).setPermission(true).setSharedUid(false).setAuth(false).setRollback(false).build();
                var lineage = new SigningCertificateLineage.Builder(old.lineage(), next.lineage())
                    .setOriginalCapabilities(capabilities).build();
                Path temporary = Files.createTempFile(lineagePath.toAbsolutePath().getParent(), "lineage-", ".tmp");
                try {
                    // SDK readFromFile/writeToFile retain handles in some releases.
                    // Byte buffers keep retries/atomic publication safe on Windows too.
                    var bytes = new ByteArrayOutputStream();
                    lineage.writeToDataSink(DataSinks.asDataSink(bytes));
                    Files.write(temporary, bytes.toByteArray());
                    checkedLineage(temporary, oldPin, nextPin, false);
                    Files.move(temporary, lineagePath); // No overwrite on retry or concurrent bootstrap.
                } finally { Files.deleteIfExists(temporary); }
            }
            checkedLineage(lineagePath, oldPin, nextPin, false);
            System.out.println("{\"lineageBase64\":\"" + Base64.getEncoder().encodeToString(Files.readAllBytes(lineagePath))
                + "\",\"installedDataTrusted\":true,\"oldKeyRollbackAllowed\":false}");
            return;
        }
        var lineage = checkedLineage(lineagePath, oldPin, nextPin, false);
        Path apk = Path.of(args[4]);
        if (action.equals("sign")) {
            Key old = load(false), next = load(true);
            identity(old.cert(), next.cert(), oldPin, nextPin);
            Path output = Path.of(args[5]);
            new ApkSigner.Builder(List.of(old.signer("previous"), next.signer("Howl")))
                .setInputApk(apk.toFile()).setOutputApk(output.toFile()).setMinSdkVersion(24)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false)
                .setSigningCertificateLineage(lineage).setMinSdkVersionForRotation(33).build().sign();
            apk = output;
        } else if (!action.equals("verify")) { throw new IllegalArgumentException(); }
        System.out.println(verify(apk, lineagePath, oldPin, nextPin));
    }
    public static void main(String[] args) {
        try { execute(args); }
        catch (Exception failure) {
            // Do not emit exception details, environment, keystore data or passwords.
            System.err.println("Official Android signing operation rejected: " + failure.getClass().getSimpleName());
            for (var frame : failure.getStackTrace()) {
                if (frame.getClassName().equals("AndroidSigning"))
                    System.err.println("Signing check: " + frame.getMethodName() + ":" + frame.getLineNumber());
            }
            System.exit(1);
        }
    }
}
