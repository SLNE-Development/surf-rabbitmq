package dev.slne.surf.rabbitmq.api.rpc

/**
 * Marks an interface as a RabbitMQ RPC service contract.
 *
 * Interfaces annotated with this annotation are processed by the Surf RabbitMQ
 * KSP processor. The processor generates the descriptor and client-side proxy
 * implementation that are used by `ClientRabbitMQApi.createRpcService` and
 * `ServerRabbitMQApi.registerRpcService`.
 *
 * An RPC service must be declared as an interface. Every RPC endpoint should be
 * a `suspend` function. The same annotated interface must be available on both
 * the client and the server classpath so both sides use the same method names,
 * parameter types, return types, and serializers.
 *
 * All RPC parameter types and return types must be serializable by the
 * `kotlinx.serialization` configuration used by the client and server APIs.
 * This includes:
 *
 * - primitive and built-in types supported by the configured serializers module,
 * - classes annotated with `@Serializable`,
 * - types with contextual serializers registered in the API's `SerializersModule`,
 * - types annotated with a custom serializer using `@Serializable(with = ...)`.
 *
 * Example service contract:
 *
 * ```kotlin
 * @RpcService
 * interface UserService {
 *     suspend fun findUserName(userId: UUID): String?
 *     suspend fun updateUserName(userId: UUID, name: String)
 * }
 * ```
 *
 * Server-side usage:
 *
 * ```kotlin
 * class UserServiceImpl : UserService {
 *     override suspend fun findUserName(userId: UUID): String? {
 *         return userRepository.findName(userId)
 *     }
 *
 *     override suspend fun updateUserName(userId: UUID, name: String) {
 *         userRepository.updateName(userId, name)
 *     }
 * }
 *
 * serverApi.registerRpcService<UserService>(UserServiceImpl())
 * ```
 *
 * Client-side usage:
 *
 * ```kotlin
 * val userService = clientApi.createRpcService<UserService>()
 *
 * val name = userService.findUserName(userId)
 * userService.updateUserName(userId, "Alex")
 * ```
 *
 * Custom serializers can be applied directly to parameter or return types by
 * annotating the type with `@Serializable(with = ...)`.
 *
 * ```kotlin
 * object UserIdSerializer : KSerializer<UserId> {
 *     // serializer implementation
 * }
 *
 * object UserDtoSerializer : KSerializer<UserDto> {
 *     // serializer implementation
 * }
 *
 * @RpcService
 * interface UserService {
 *     suspend fun findUser(
 *         userId: @Serializable(with = UserIdSerializer::class) UserId
 *     ): @Serializable(with = UserDtoSerializer::class) UserDto
 * }
 * ```
 *
 * Contextual serializers can be used by annotating the parameter or return type
 * with `@Contextual`. The matching serializer must be registered in the
 * `SerializersModule` passed to the client and server API creation methods.
 *
 * ```kotlin
 * @RpcService
 * interface UserService {
 *     suspend fun findUser(userId: @Contextual UserId): @Contextual UserDto
 * }
 * ```
 *
 * Both sides must use compatible serializers. If the client and server use
 * different serializers for the same RPC type, calls may fail during encoding,
 * decoding, or produce incompatible payloads.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class RpcService