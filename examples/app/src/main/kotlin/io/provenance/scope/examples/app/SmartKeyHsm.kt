package io.provenance.scope.examples.app

import com.fortanix.sdkms.v1.ApiClient
import com.fortanix.sdkms.v1.api.AuthenticationApi
import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import com.fortanix.sdkms.v1.auth.ApiKeyAuth
import io.grpc.ManagedChannelBuilder
import io.provenance.scope.contract.annotations.Record
import io.provenance.scope.contract.proto.Specifications.PartyType
import io.provenance.scope.encryption.model.SmartKeyRef
import io.provenance.scope.encryption.util.toJavaPublicKey
import io.provenance.scope.examples.SimpleExample.ExampleName
import io.provenance.scope.examples.app.utils.SingleTx
import io.provenance.scope.examples.app.utils.TransactionService
import io.provenance.scope.examples.app.utils.getScope
import io.provenance.scope.examples.app.utils.persistBatchToProvenance
import io.provenance.scope.examples.contract.SimpleExampleContract
import io.provenance.scope.examples.contract.SimpleExampleScopeSpecification
import io.provenance.scope.sdk.Affiliate
import io.provenance.scope.sdk.Client
import io.provenance.scope.sdk.ClientConfig
import io.provenance.scope.sdk.SharedClient
import io.provenance.scope.sdk.SignedResult
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ExampleNameHydrateHsm(
    @Record("name") val name: ExampleName,
)

fun main(args: Array<String>) {
    println("Executing example with Smart Key HSM crypto operations!")

    // Creates Provenance grpc client. This is used for fetching Provenance account information, as well as
    // simulating and broadcasting TXs.
    val provenanceUri = URI(System.getenv("PROVENANCE_GRPC_URL"))
    val channel = ManagedChannelBuilder
        .forAddress(provenanceUri.host, provenanceUri.port)
        .also {
            if (provenanceUri.scheme.endsWith("s")) {
                it.useTransportSecurity()
            } else {
                it.usePlaintext()
            }
        }
        .build()
    val transactionService = TransactionService(System.getenv("CHAIN_ID"), channel)

    // Creates a Smart Key API Client
    val smartKeyApiKey = System.getenv("SMART_KEY_API_KEY")
    val smartKeyClient = ApiClient().apply {
        setBasicAuthString(smartKeyApiKey)
        com.fortanix.sdkms.v1.Configuration.setDefaultApiClient(this)

        // authenticate with API
        val authResponse = AuthenticationApi(this).authorize()
        val auth = getAuthentication("bearerToken") as ApiKeyAuth
        auth.apiKey = authResponse.accessToken
        auth.apiKeyPrefix = "Bearer"
    }
    val signAndVerifyApi = SignAndVerifyApi(smartKeyClient)

    // Creates a P8e scope client that will be used for contract execution.
    val publicKey = System.getenv("SMART_KEY_PUBLIC_KEY").toJavaPublicKey()
    val smartKeyUuid = UUID.fromString(System.getenv("SMART_KEY_UUID"))
    val smartKeyRef = SmartKeyRef(
        publicKey = publicKey,
        uuid = smartKeyUuid,
        signAndVerifyApi = signAndVerifyApi,
    )
    val config = ClientConfig(
        cacheJarSizeInBytes = 0L,
        cacheSpecSizeInBytes = 0L,
        cacheRecordSizeInBytes = 0L,

        osGrpcUrl = URI(System.getenv("OS_GRPC_URL")),
        osGrpcDeadlineMs = 30 * 1_000L,

        mainNet = false,
    )
    // We are choosing to use the same underlying HSM key for signing and encryption, but they can also be separate
    // Smart Key references.
    val affiliate = Affiliate(
        encryptionKeyRef = smartKeyRef,
        signingKeyRef = smartKeyRef,
        partyType = PartyType.OWNER,
    )
    val sdk = Client(SharedClient(config = config), affiliate)

    // A UUID used to create and update this scope. This can be thought of as the unique primary key
    // referencing the scope we will be creating below.
    val scopeUuid = UUID.randomUUID()

    try {
        // Creates and executes a new session on a new scope that contains a single record.
        val session = sdk.newSession(SimpleExampleContract::class.java, SimpleExampleScopeSpecification::class.java)
            .setScopeUuid(scopeUuid)
            .addProposedRecord(
                "name",
                ExampleName.newBuilder()
                    .setFirstName("Art")
                    .setLastName("Vandelay")
                    .build()
            )
            .build()

        // A single party contract will always return a batch of messages that can be persisted to Provenance.
        when (val result = sdk.execute(session)) {
            is SignedResult -> persistBatchToProvenance(transactionService, SingleTx(result), affiliate.signingKeyRef)
            else -> throw IllegalStateException("Must be a signed result since this is a single party contract.")
        }

        // Fetches the latest scope from Provenance and hydrates hashes from Object Store.
        val scopeResponse = getScope(channel, scopeUuid)
        val scope = sdk.hydrate(ExampleNameHydrateHsm::class.java, scopeResponse)
        println("ExampleName record data = $scope")
    } catch (e: Exception) {
        println(e.printStackTrace())
    } finally {
        // Cleanly shuts down both the sdk client and the Provenance grpc channel.
        channel.shutdown()
        if (!channel.awaitTermination(10, TimeUnit.SECONDS)) {
            println("Could not shutdown Provenance managed channel cleanly!")
        }

        sdk.inner.close()
        if (!sdk.inner.awaitTermination(10, TimeUnit.SECONDS)) {
            println("Could not shutdown sdk managed channel cleanly!")
        }
    }
}
