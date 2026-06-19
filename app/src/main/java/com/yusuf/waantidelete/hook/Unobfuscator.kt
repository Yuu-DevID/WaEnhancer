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
        val fStatusKeyClass = loadFStatusKeyClass(classLoader)
        val result = bridge.findMethod {
            matcher {
                addUsingString("RevokeStatusManager/failed")
            }
        }
        if (result.isEmpty()) throw RuntimeException("AntiRevoke FStatus method not found")

        for (methodData in result) {
            val method = methodData.getMethodInstance(classLoader)
            if (method.parameterCount > 0 &&
                fStatusKeyClass.isAssignableFrom(method.parameterTypes[0])) {
                return method
            }
        }
        throw RuntimeException("AntiRevoke FStatus method not found")
    }

    @JvmStatic
    fun loadFStatusKeyClass(classLoader: ClassLoader): Class<*> {
        val result = bridge.findClass {
            matcher {
                addUsingString("Key(id=")
                addUsingString("senderJid")
            }
        }
        if (result.isEmpty()) throw RuntimeException("FStatusKey class not found")
        return result[0].getInstance(classLoader)
    }

    @JvmStatic
    fun loadFStatusClass(classLoader: ClassLoader): Class<*> {
        val result = bridge.findClass {
            matcher {
                addUsingString("FStatus state")
            }
        }
        if (result.isEmpty()) throw RuntimeException("FStatus class not found")
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
}
