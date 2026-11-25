package dev.kair.app.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.security.Provider
import javax.net.ssl.SSLSocket

/**
 * U90 配对链路的依赖守卫（真机事故回归，见任务书 §4.0）。
 *
 * 事故：不带 Conscrypt 时 libadb 会去反射**平台**的 `com.android.org.conscrypt.Conscrypt`
 * `exportKeyingMaterial`，而那是非 SDK 接口 —— Android 12+ 反射直接抛 `NoSuchMethodException`，
 * EKM 拿不到、SPAKE2 密钥算不出，配对必挂。修复是引入 `org.conscrypt:conscrypt-android`
 * （libadb 的 `SslUtils` 只要发现 `org.conscrypt.OpenSSLProvider` 就走自带实现）。
 *
 * 本测试把「libadb 到底会怎么反射」在原地跑一遍：依赖被拿掉、或升到删了该方法的版本，这里先红，
 * 不必再等一次真机配对失败。
 *
 * 取类统一用 `Class.forName(name, false, loader)`：**不触发静态初始化**，因此不会在 JVM 上尝试
 * `System.loadLibrary("conscrypt_jni")`（那是 Android 的 .so）；本测试只看方法面。
 */
class AdbTlsProviderGuardTest {
    @Test
    fun `自带 Conscrypt 在运行时 classpath 上`() {
        val provider = Class.forName(OPENSSL_PROVIDER, false, javaClass.classLoader)
        assertTrue(
            "OpenSSLProvider 必须是 Provider 子类",
            Provider::class.java.isAssignableFrom(provider),
        )
        // libadb 用 getDeclaredConstructor() 造 provider；没有无参构造就走平台路径 → 事故复现。
        assertNotNull(
            "OpenSSLProvider 必须有无参构造",
            provider.declaredConstructors.firstOrNull { it.parameterCount == 0 },
        )
    }

    @Test
    fun `libadb 反射的 EKM 签名在自带 Conscrypt 上可命中`() {
        val conscrypt = Class.forName(CONSCRYPT, false, javaClass.classLoader)
        val ekm = conscrypt.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java,
            String::class.java,
            ByteArray::class.java,
            Integer.TYPE,
        )
        assertTrue("库按静态方法调用", Modifier.isStatic(ekm.modifiers))
        assertTrue("库按 public 方法反射", Modifier.isPublic(ekm.modifiers))
        assertEquals(ByteArray::class.java, ekm.returnType)
    }

    private companion object {
        const val OPENSSL_PROVIDER = "org.conscrypt.OpenSSLProvider"
        const val CONSCRYPT = "org.conscrypt.Conscrypt"
    }
}
