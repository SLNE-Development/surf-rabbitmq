package dev.slne.surf.rabbitmq.api.exception

open class SurfRabbitHandlerRegistrationException(message: String, cause: Throwable? = null) :
    SurfRabbitException(message, cause)

class SurfRabbitDuplicateHandlerException(parameterType: String, existing: String, incoming: String) :
    SurfRabbitHandlerRegistrationException(
        "Duplicate handler for '$parameterType': already registered by '$existing', cannot register '$incoming'"
    )

class SurfRabbitInvalidHandlerParameterCountException(qualifiedName: String, methodName: String, count: Int) :
    SurfRabbitHandlerRegistrationException(
        "Handler method '$qualifiedName::$methodName' must have exactly one parameter, but has $count"
    )

class SurfRabbitInvalidHandlerParameterTypeException(qualifiedName: String, methodName: String, parameterType: String) :
    SurfRabbitHandlerRegistrationException(
        "Handler method '$qualifiedName::$methodName' parameter type '$parameterType' must be a subclass of RabbitRequestPacket"
    )

class SurfRabbitHandlerNotAccessibleException(methodName: String, className: String, packageName: String) :
    SurfRabbitHandlerRegistrationException(
        "Method '$methodName' in '$className' is not accessible via privateLookupIn " +
                "— ensure the package '$packageName' is opened to the surf-rabbitmq module. " +
                "Cannot register as request handler."
    )

class SurfRabbitApiAlreadyFrozenException :
    SurfRabbitHandlerRegistrationException("Cannot register request handlers after the API has been frozen")