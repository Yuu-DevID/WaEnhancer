package com.yusuf.waantidelete.hook

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method

object Unobfuscator {

    private const val TAG = "WaAntiDelete"
    private lateinit var bridge: DexKitBridge

    init {
        System.loadLibrary("dexkit")
    }

    @JvmStatic
    fun init(path: String): Boolean {
        return try {
            bridge = DexKitBridge.create(path)
            true
        } catch (e: Exception) {
            Log.e(TAG, "DexKit init failed", e)
            false
        }
    }

    @JvmStatic
    fun loadFMessageClass(classLoader: ClassLoader): Class<*> {
        val result = bridge.findClass {
            matcher {
                addUsingString("FMessage/getSenderUserJid/key.id")
            }
        }
        if (result.isEmpty()) throw RuntimeException("FMessage class not found")
        return result[0].getInstance(classLoader)
    }

    @JvmStatic
    fun loadAntiRevokeMessageMethod(classLoader: ClassLoader): Method {
        for (s in listOf("msgstore/edit/revoke", "msgstore/revoking/")) {
            val method = findMethodByString(classLoader, s)
            if (method != null) return method
        }
        throw RuntimeException("AntiRevoke message method not found")
    }

    @JvmStatic
    fun loadAntiRevokeFStatusMethod(classLoader: ClassLoader): Method {
        try {
            val clazz = findClassByString(classLoader, "RevokeStatusManager/failed")
                ?: throw RuntimeException("RevokeStatusManager class not found")
            Log.d(TAG, "Found RevokeStatusManager class: ${clazz.name}")

            val fStatusKeyClass = loadFStatusKeyClass(classLoader)
            Log.d(TAG, "FStatusKey class: ${fStatusKeyClass.name}")

            for (method in clazz.declaredMethods) {
                if (method.parameterCount > 0 &&
                    fStatusKeyClass.isAssignableFrom(method.parameterTypes[0])) {
                    Log.d(TAG, "Found FStatus revoke method: ${method.name}")
                    return method
                }
            }

            for (method in clazz.methods) {
                if (method.parameterCount > 0 &&
                    fStatusKeyClass.isAssignableFrom(method.parameterTypes[0])) {
                    Log.d(TAG, "Found FStatus revoke method (via methods): ${method.name}")
                    return method
                }
            }

            Log.w(TAG, "Methods in RevokeStatusManager:")
            for (m in clazz.declaredMethods) {
                val params = m.parameterTypes.joinToString { it.simpleName }
                Log.w(TAG, "  ${m.name}($params) -> ${m.returnType.simpleName}")
            }

            throw RuntimeException("FStatus revoke method not found in ${clazz.name}")
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("loadAntiRevokeFStatusMethod failed", e)
        }
    }

    @JvmStatic
    fun loadFStatusKeyClass(classLoader: ClassLoader): Class<*> {
        val result = bridge.findClass {
            matcher {
                addUsingString("Key(id=")
                addUsingString("senderJid")
            }
        }
        if (result.isEmpty()) {
            Log.w(TAG, "FStatusKey not found with 'Key(id=' + 'senderJid', trying alternatives...")
            val alt1 = findClassByString(classLoader, "FStatusKey")
            if (alt1 != null) return alt1

            val alt2 = findClassByString(classLoader, "FStatus state")
            if (alt2 != null) {
                for (inner in alt2.declaredClasses) {
                    if (inner.simpleName?.contains("Key") == true) {
                        Log.d(TAG, "Found FStatusKey via inner class: ${inner.name}")
                        return inner
                    }
                }
            }

            throw RuntimeException("FStatusKey class not found")
        }
        return result[0].getInstance(classLoader)
    }

    @JvmStatic
    fun loadMessageKeyField(classLoader: ClassLoader): Field {
        val fMessageClass = loadFMessageClass(classLoader)
        val result = bridge.findClass {
            matcher {
                fieldCount(3)
                addMethod {
                    name("toString")
                    addUsingString("Key")
                }
            }
        }
        if (result.isEmpty()) throw RuntimeException("MessageKey class not found")

        for (classData in result) {
            val keyClass = classData.getInstance(classLoader)
            for (f in fMessageClass.declaredFields) {
                if (keyClass.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    return f
                }
            }
        }
        throw RuntimeException("MessageKey field not found")
    }

    @JvmStatic
    fun loadViewOnceMethods(classLoader: ClassLoader): Array<Method> {
        val result = bridge.findMethod {
            matcher {
                addUsingString("INSERT_VIEW_ONCE_SQL")
            }
        }
        if (result.isEmpty()) throw RuntimeException("ViewOnce SQL method not found")

        val methodData = result[0]
        val invokes = methodData.invokes
        val methods = mutableListOf<Method>()

        for (m in invokes) {
            try {
                val method = m.getMethodInstance(classLoader)
                if (method.declaringClass.isInterface &&
                    method.declaringClass.declaredMethods.size == 2) {
                    val iface = method.declaringClass
                    val implementingClasses = bridge.findClass {
                        matcher {
                            addInterface(iface.name)
                        }
                    }
                    for (c in implementingClasses) {
                        val clazz = c.getInstance(classLoader)
                        for (m2 in clazz.declaredMethods) {
                            if (m2.parameterCount == 1 &&
                                m2.parameterTypes[0] == Int::class.javaPrimitiveType &&
                                m2.returnType == Void.TYPE) {
                                methods.add(m2)
                            }
                        }
                    }
                    if (methods.isNotEmpty()) return methods.toTypedArray()
                }
            } catch (_: Throwable) {}
        }
        throw RuntimeException("ViewOnce methods not found")
    }

    private fun findMethodByString(classLoader: ClassLoader, searchString: String): Method? {
        val result = bridge.findMethod {
            matcher {
                addUsingString(searchString)
            }
        }
        if (result.isEmpty()) return null
        for (methodData in result) {
            try {
                return methodData.getMethodInstance(classLoader)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findClassByString(classLoader: ClassLoader, searchString: String): Class<*>? {
        val result = bridge.findClass {
            matcher {
                addUsingString(searchString)
            }
        }
        if (result.isEmpty()) return null
        return try {
            result[0].getInstance(classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}
