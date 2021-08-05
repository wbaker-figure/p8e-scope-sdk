package io.provenance.scope

import arrow.core.Either
import arrow.core.identity
import com.google.protobuf.Message
import io.provenance.scope.contract.proto.Contracts.ExecutionResult
import io.provenance.scope.contract.proto.Contracts.Record
import io.provenance.scope.contract.proto.Contracts.Contract
import io.provenance.scope.contract.spec.P8eContract
import io.provenance.scope.definition.DefinitionService
import io.provenance.scope.encryption.crypto.SignerImpl
import io.provenance.scope.encryption.model.KeyRef
import io.provenance.scope.util.orThrowContractDefinition
import io.provenance.scope.util.toOffsetDateTimeProv
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.concurrent.thread

class ContractWrapper(
    private val executor: ExecutorService,
    private val contractSpecClass: Class<out Any>,
    private val encryptionKeyRef: KeyRef,
    private val definitionService: DefinitionService,
    private val contractBuilder: Contract.Builder,
    private val signer: SignerImpl
) {
    private val records = buildRecords()

    val contractClass = definitionService.loadClass(
        contractBuilder.definition
    ).takeIf {
        contractSpecClass.isAssignableFrom(it) &&
                P8eContract::class.java.isAssignableFrom(it)
    }.orThrowContractDefinition("Contract class ${contractBuilder.definition.resourceLocation.classname} must implement ${contractSpecClass.name} and extend ${P8eContract::class.java.name}")

    private val constructor = getConstructor(contractClass)

    private val constructorParameters = getConstructorParameters(constructor, records)

    private val contract = (constructor.newInstance(*constructorParameters.toTypedArray()) as P8eContract)
        .also { it.currentTime.set(contractBuilder.startTime.toOffsetDateTimeProv()) }

    val functions = contractBuilder.considerationsBuilderList
        .filter { it.result == ExecutionResult.getDefaultInstance() }
        .map { consideration -> consideration to getConsiderationMethod(contract.javaClass, consideration.considerationName) }
        .map { (consideration, method) -> Function(encryptionKeyRef, signer, definitionService, contract, consideration, method, records) }

    private fun getConstructor(
        clazz: Class<*>
    ): Constructor<*> =
        clazz.declaredConstructors
            .takeIf { it.size == 1 }
            ?.first()
            .orThrowContractDefinition("Class ${clazz.name} must have only one constructor.")

    private fun getConstructorParameters(
        constructor: Constructor<*>,
        records: List<RecordInstance>
    ): List<Any> =
        constructor.parameters
            .map { getParameterRecord(it, records) }
            .filterNotNull()
            .map { it.messageOrCollection.fold(::identity, ::identity) }


    private fun getParameterRecord(
        parameter: Parameter,
        records: List<RecordInstance>
    ): RecordInstance? {
        return records.find {
            parameter.getAnnotation(io.provenance.scope.contract.annotations.Record::class.java)?.name == it.name
        }
    }

    private fun getConsiderationMethod(
        contractClass: Class<*>,
        name: String
    ): Method {
        return contractClass.methods
            .filter { it.isAnnotationPresent(io.provenance.scope.contract.annotations.Function::class.java) }
            .find { it.name == name }
            .orThrowContractDefinition("Unable to find method on class ${contractClass.name} with annotation ${io.provenance.scope.contract.annotations.Function::class.java} with name $name")
    }

    private fun buildRecords(): List<RecordInstance> {
        return contractBuilder.inputsList
            .filter { it.dataLocation.ref.hash.isNotEmpty() }
            .toRecordInstance(encryptionKeyRef)
            .takeIf { records -> records.map { it.name }.toSet().size == records.size }
            .orThrowContractDefinition("Found duplicate record messages by name.")
    }

    private fun List<Record>.toRecordInstance(
        encryptionKeyRef: KeyRef
    ): List<RecordInstance> {
        val recordMap: Map<String, List<Message>> = groupByTo(mutableMapOf(), { it.name }) { record ->
            val completableFuture = CompletableFuture<Message>()
            thread(start = false) {
                definitionService.forThread {
                    try {
                        completableFuture.complete(
                            definitionService.loadProto(
                                encryptionKeyRef,
                                record,
                                signer
                            )
                        )
                    } catch (t: Throwable) {
                        completableFuture.completeExceptionally(t)
                    }
                }
            }.let(executor::submit)
            completableFuture
        }.map { (name, futures) ->
            name to futures.map { it.get() }
        }.toMap()

        val records = recordMap.entries
            .filter { it.value.size == 1 }
            .flatMap { (name, messages) ->
                messages.map { message ->
                    RecordInstance(
                        name,
                        message.javaClass,
                        Either.Left(message)
                    )
                }
            }.toMutableList()

        recordMap.entries
            .filter { it.value.size > 1 }
            .map { (name, messages) ->
                RecordInstance(
                    name,
                    messages.first().javaClass,
                    Either.Right(messages)
                )
            }.let(records::addAll)
        return records
    }
}
