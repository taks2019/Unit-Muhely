package hu.unitool.app

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Saját, egyszer generált kulccsal írja alá az APK-t (v1+v2+v3). */
object Signer {
    private fun keyFiles(ctx: Context) = File(ctx.filesDir, "sign.pk8") to File(ctx.filesDir, "sign.der")

    private fun ensureKey(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val (kf, cf) = keyFiles(ctx)
        if (!kf.exists() || !cf.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Unity Muhely, O=Unity Muhely")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L),
                Date(now + 30L * 365 * 86_400_000L), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(cert.encoded)
        }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream()) as X509Certificate
        return key to cert
    }

    fun sign(ctx: Context, input: File, output: File) {
        val (key, cert) = ensureKey(ctx)
        val cfg = ApkSigner.SignerConfig.Builder("unitool", key, listOf(cert)).build()
        output.delete()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(24)
            .build()
            .sign()
    }
}
