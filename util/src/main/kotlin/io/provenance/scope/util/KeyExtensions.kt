package io.provenance.scope.util

import io.provenance.scope.contract.proto.PublicKeys
import io.provenance.scope.encryption.ecies.ECUtils
import io.provenance.scope.encryption.proto.PK
import org.bouncycastle.util.encoders.Hex
import java.security.PrivateKey
import java.security.PublicKey


fun PublicKey.toPublicKeyProto(): PublicKeys.PublicKey =
    PublicKeys.PublicKey.newBuilder()
        .setCurve(PublicKeys.KeyCurve.SECP256K1)
        .setType(PublicKeys.KeyType.ELLIPTIC)
        .setPublicKeyBytes(ECUtils.convertPublicKeyToBytes(this).toByteString())
        .setCompressed(false)
        .build()

fun PublicKeys.PublicKey.toHex() = this.toByteArray().toHexString()

fun PK.PrivateKey.toPrivateKey(): PrivateKey =
    this.let {
        require(it.curve == PK.KeyCurve.SECP256K1) { "Unsupported Key Curve" }
        ECUtils.convertBytesToPrivateKey(it.keyBytes.toByteArray())
    }

fun PK.PublicKey.toPublicKey(): PublicKey =
    this.let {
        require(it.curve == PK.KeyCurve.SECP256K1) {"Unsupported Key Curve"}
        ECUtils.convertBytesToPublicKey(it.publicKeyBytes.toByteArray())
    }

fun PublicKey.toHex() = toPublicKeyProto().toHex()

fun String.toPublicKeyProto(): PK.PublicKey = PK.PublicKey.parseFrom(Hex.decode(this))

fun String.toPrivateKeyProto(): PK.PrivateKey = PK.PrivateKey.parseFrom(Hex.decode(this))

fun String.toJavaPublicKey() = toPublicKeyProto().toPublicKey()
fun String.toJavaPrivateKey() = toPrivateKeyProto().toPrivateKey()
