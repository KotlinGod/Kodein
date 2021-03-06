package com.github.salomonbrys.kodein.jxinject

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.TT
import com.github.salomonbrys.kodein.TypeToken
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

/**
 * Injector that allows to inject instances that use `javax.inject.*` annotations.
 *
 * @property kodein The kodein object to use to retrieve injections.
 */
class JxInjector(val kodein: Kodein) {

    private val _qualifiers = HashMap<Class<out Annotation>, (Annotation) -> Any>()

    private val _setters = ConcurrentHashMap<Class<*>, List<(Any) -> Any>>()

    private val _constructors = ConcurrentHashMap<Class<*>, () -> Any>()

    init {
        registerQualifier(Named::class.java) { it.value }
    }

    internal fun <T: Annotation> registerQualifier(cls: Class<T>, tagProvider: (T) -> Any) {
        @Suppress("UNCHECKED_CAST")
        _qualifiers.put(cls, tagProvider as (Annotation) -> Any)
    }

    private fun _getTagFromQualifier(el: AnnotatedElement): Any? {
        _qualifiers.forEach {
            val qualifier = el.getAnnotation(it.key)
            if (qualifier != null)
                return it.value.invoke(qualifier)
        }
        return null
    }

    private interface Element : AnnotatedElement {
        val classType: Class<*>
        val genericType: Type
        override fun isAnnotationPresent(annotationClass: Class<out Annotation>) = getAnnotation(annotationClass) != null
        override fun getDeclaredAnnotations(): Array<out Annotation> = annotations
        @Suppress("UNCHECKED_CAST")
        override fun <T : Annotation?> getAnnotation(annotationClass: Class<T>) = annotations.firstOrNull { annotationClass.isAssignableFrom(it.javaClass) } as T?
        override fun toString(): String
    }

    private fun _getter(element: Element): Kodein.() -> Any? {
        val tag = _getTagFromQualifier(element)

        val shouldErase = element.isAnnotationPresent(ErasedBinding::class.java)

        fun Type.boundType() = when {
            shouldErase -> rawType()
            this is WildcardType -> upperBounds[0]
            else -> this
        }

        val isOptional = element.isAnnotationPresent(OrNull::class.java)

        fun getterFunction(getter: Kodein.() -> Any?) = getter

        return when {
            element.classType == Lazy::class.java -> { // Must be first
                val boundType = (element.genericType as ParameterizedType).actualTypeArguments[0].boundType()
                class LazyElement : Element by element {
                    override val classType: Class<*> get() = boundType.rawType()
                    override val genericType: Type get() = boundType
                    override fun toString() = element.toString()
                }
                val getter = _getter(LazyElement())

                getterFunction { lazy { getter() } }
            }
            element.isAnnotationPresent(ProviderFun::class.java) -> {
                if (element.classType != Function0::class.java)
                    throw IllegalArgumentException("When visiting $element, @ProviderFun annotated members must be of type Function0 () -> T")
                @Suppress("UNCHECKED_CAST")
                val boundType = TT((element.genericType as ParameterizedType).actualTypeArguments[0].boundType()) as TypeToken<out Any>
                when (isOptional) {
                    true  -> getterFunction { ProviderOrNull(boundType, tag) }
                    false -> getterFunction { Provider(boundType, tag) }
                }
            }
            element.classType == Provider::class.java -> {
                @Suppress("UNCHECKED_CAST")
                val boundType = TT((element.genericType as ParameterizedType).actualTypeArguments[0].boundType()) as TypeToken<out Any>
                fun (() -> Any).toJavaxProvider() = javax.inject.Provider { invoke() }
                when (isOptional) {
                    true  -> getterFunction { ProviderOrNull(boundType, tag)?.toJavaxProvider() }
                    false -> getterFunction { Provider(boundType, tag).toJavaxProvider() }
                }
            }
            element.isAnnotationPresent(FactoryFun::class.java) -> {
                if (element.classType != Function1::class.java)
                    throw IllegalArgumentException("When visiting $element, @FactoryFun annotated members must be of type Function1 (A) -> T")
                val fieldType = element.genericType as ParameterizedType
                val argType = TT(fieldType.actualTypeArguments[0].lower())
                @Suppress("UNCHECKED_CAST")
                val boundType = TT(fieldType.actualTypeArguments[1].boundType()) as TypeToken<out Any>
                when (isOptional) {
                    true  -> getterFunction { FactoryOrNull(argType, boundType, tag) }
                    false -> getterFunction { Factory(argType, boundType, tag) }
                }
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val boundType = if (shouldErase) TT(element.classType) else TT(element.genericType) as TypeToken<out Any>
                when (isOptional) {
                    true  -> getterFunction { InstanceOrNull(boundType, tag) }
                    false -> getterFunction { Instance(boundType, tag) }
                }
            }
        }
    }

    private fun <M: AccessibleObject> _fillSetters(
        members: Array<M>,
        elements: M.() -> Array<Element>,
        call: M.(Any, Array<Any?>) -> Unit,
        setters: MutableList<(Any) -> Any>
    ) {
        members
            .filter { it.isAnnotationPresent(Inject::class.java) }
            .map { member ->
                val getters = member.elements()
                    .map { _getter(it) }
                    .toTypedArray()

                val isAccessible = member.isAccessible

                setters += { receiver ->
                    val arguments = Array<Any?>(getters.size) { null }
                    getters.forEachIndexed { i, getter -> arguments[i] = kodein.getter() }

                    if (!isAccessible) member.isAccessible = true
                    try {
                        member.call(receiver, arguments)
                    }
                    finally {
                        if (!isAccessible) member.isAccessible = false
                    }
                }
            }
    }

    private tailrec fun _fillMembersSetters(cls: Class<*>, setters: MutableList<(Any) -> Any>) {
        if (cls == Any::class.java)
            return

        _setters[cls]?.let {
            setters += it
            return
        }

        @Suppress("DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE")
        class FieldElement(private val _field: Field) : Element, AnnotatedElement by _field {
            override val classType: Class<*> get() = _field.type
            override val genericType: Type get() = _field.genericType
            override fun toString() = _field.toString()
        }

        _fillSetters(
            members = cls.declaredFields,
            elements = { arrayOf(FieldElement(this)) },
            call = { receiver, values -> set(receiver, values[0]) },
            setters = setters
        )

        class ParameterElement(private val _method: Method, private val _index: Int) : Element {
            override val classType: Class<*> get() = _method.parameterTypes[_index]
            override val genericType: Type get() = _method.genericParameterTypes[_index]
            override fun getAnnotations() = _method.parameterAnnotations[_index]
            override fun toString() = "Parameter ${_index + 1} of $_method"
        }

        _fillSetters(
            members = cls.declaredMethods,
            elements = { (0 until parameterTypes.size).map { ParameterElement(this, it) }.toTypedArray() },
            call = { receiver, values -> invoke(receiver, *values) },
            setters = setters
        )

       return _fillMembersSetters(cls.superclass, setters)
    }

    private fun _createSetters(cls: Class<*>): List<(Any) -> Any> {
        val setters = ArrayList<(Any) -> Any>()
        _fillMembersSetters(cls, setters)
        return setters
    }

    private fun _findSetters(cls: Class<*>): List<(Any) -> Any> = _setters.getOrPut(cls) { _createSetters(cls) }

    /**
     * Injects all fields and methods annotated with `@Inject` in `receiver`.
     *
     * @param receiver The object to inject.
     */
    fun inject(receiver: Any) {
        _findSetters(receiver.javaClass).forEach { it(receiver) }
    }

    private fun _createConstructor(cls: Class<*>): () -> Any {
        val constructor = cls.declaredConstructors.firstOrNull { it.isAnnotationPresent(Inject::class.java) }
            ?:  if (cls.declaredConstructors.size == 1) cls.declaredConstructors[0]
                else throw IllegalArgumentException("Class ${cls.name} must either have only one constructor or an @Inject annotated constructor")

        class ConstructorElement(private val _index: Int) : Element {
            override val classType: Class<*> get() = constructor.parameterTypes[_index]
            override val genericType: Type get() = constructor.genericParameterTypes[_index]
            override fun getAnnotations() = constructor.parameterAnnotations[_index]
            override fun toString() = "Parameter ${_index + 1} of $constructor"
        }

        val getters = (0 until constructor.parameterTypes.size).map { _getter(ConstructorElement(it)) }

        val isAccessible = constructor.isAccessible

        return {
            val arguments = Array<Any?>(getters.size) { null }
            getters.forEachIndexed { i, getter -> arguments[i] = kodein.getter() }

            if (!isAccessible) constructor.isAccessible = true
            try {
                constructor.newInstance(*arguments)
            }
            finally {
                if (!isAccessible) constructor.isAccessible = false
            }
        }
    }

    private fun _findConstructor(cls: Class<*>) = _constructors.getOrPut(cls) { _createConstructor(cls) }

    /** @suppress */
    @JvmOverloads
    fun <T: Any> newInstance(cls: Class<T>, injectFields: Boolean = true): T {
        val constructor = _findConstructor(cls)

        @Suppress("UNCHECKED_CAST")
        val instance = constructor.invoke() as T

        if (injectFields)
            inject(instance)

        return instance
    }

    /**
     * Creates a new instance of the given type.
     *
     * @param T The type of object to create.
     * @param injectFields Whether to inject the fields & methods of he newly created instance before returning it.
     */
    inline fun <reified T: Any> newInstance(injectFields: Boolean = true) = newInstance(T::class.java, injectFields)
}
