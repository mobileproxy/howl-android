import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Reports the certificate selected by the official verifier for each SDK.
 *  This does not install the APK or prove Android data migration. */
public final class SigningRotationLab {
    public static void main(String[] args) throws Exception {
        for (int sdk = Integer.parseInt(args[1]); sdk <= Integer.parseInt(args[2]); sdk++) {
            var result = new ApkVerifier.Builder(new File(args[0]))
                .setMinCheckedPlatformVersion(sdk)
                .setMaxCheckedPlatformVersion(sdk).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().size() != 1) {
                throw new IllegalStateException("Signature verification failed for SDK " + sdk + ": " + result.getErrors());
            }
            var fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(result.getSignerCertificates().get(0).getEncoded());
            System.out.println(sdk + " " + HexFormat.of().formatHex(fingerprint));
        }
    }
}
