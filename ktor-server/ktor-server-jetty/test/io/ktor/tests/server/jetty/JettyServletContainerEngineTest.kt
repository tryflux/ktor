package io.ktor.tests.server.jetty

import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.servlet.*
import io.ktor.server.testing.*
import kotlinx.atomicfu.*
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.*
import org.eclipse.jetty.servlet.*
import org.junit.Ignore
import java.security.*
import javax.servlet.*
import javax.servlet.http.*
import kotlin.test.*

class JettyAsyncServletContainerEngineTest :
    EngineTestSuite<JettyApplicationEngineBase, JettyApplicationEngineBase.Configuration>(Servlet(async = true))

class JettyBlockingServletContainerEngineTest :
    EngineTestSuite<JettyApplicationEngineBase, JettyApplicationEngineBase.Configuration>(Servlet(async = false)) {
    @Ignore
    override fun testUpgrade() {}
}

// the factory and engine are only suitable for testing
// you shouldn't use it for production code

private class Servlet(private val async: Boolean) : ApplicationEngineFactory<JettyServletApplicationEngine, JettyApplicationEngineBase.Configuration> {
    override fun create(environment: ApplicationEngineEnvironment, configure: JettyApplicationEngineBase.Configuration.() -> Unit): JettyServletApplicationEngine {
        return JettyServletApplicationEngine(environment, configure, async)
    }
}

@UseExperimental(EngineAPI::class)
private class JettyServletApplicationEngine(
    environment: ApplicationEngineEnvironment,
    configure: JettyApplicationEngineBase.Configuration.() -> Unit,
    async: Boolean
) : JettyApplicationEngineBase(environment, configure) {
    init {
        val servletHandler = ServletContextHandler().apply {
            classLoader = environment.classLoader
            setAttribute(ServletApplicationEngine.ApplicationEngineEnvironmentAttributeKey, environment)

            insertHandler(
                ServletHandler().apply {
                    val h = ServletHolder("ktor-servlet", ServletApplicationEngine::class.java).apply {
                        isAsyncSupported = async
                        registration.setLoadOnStartup(1)
                        registration.setMultipartConfig(MultipartConfigElement(System.getProperty("java.io.tmpdir")))
                        registration.setAsyncSupported(async)
                    }

                    addServlet(h)
                    addServletMapping(ServletMapping().apply {
                        pathSpecs = arrayOf("*.", "/*")
                        servletName = "ktor-servlet"
                    })
            })
        }

        if (async) {
            server.handler = servletHandler
        } else {
            server.handler = JavaSecurityHandler().apply {
                handler = servletHandler
            }
        }
    }
}

private class JavaSecurityHandler : HandlerWrapper() {
    override fun handle(
        target: String?,
        baseRequest: Request?,
        request: HttpServletRequest?,
        response: HttpServletResponse?
    ) {
        val oldSecurityManager: SecurityManager? = System.getSecurityManager()
        val securityManager = RestrictThreadCreationSecurityManager(oldSecurityManager)
        System.setSecurityManager(securityManager)
        try {
            super.handle(target, baseRequest, request, response)
        } finally {
            securityManager.allowSwitchToSecurityManager(oldSecurityManager)
            System.setSecurityManager(oldSecurityManager)
        }
    }
}

private class RestrictThreadCreationSecurityManager(val delegate: SecurityManager?) : SecurityManager() {
    private val allowSwitchBack = atomic(false)

    fun allowSwitchToSecurityManager(manager: SecurityManager?) {
        if (!allowSwitchBack.compareAndSet(false, true)) {
            throw SecurityException("Could be allowed only once")
        }
    }

    override fun checkPermission(perm: Permission?) {
        if (perm is RuntimePermission && perm.name == "modifyThreadGroup") {
            if (currentStackTrace().any { it.className == "org.eclipse.jetty.util.thread.QueuedThreadPool" &&
                it.methodName == "newThread" }) return // allow

            throw SecurityException("Thread modifications are not allowed")
        }
        if (perm is RuntimePermission && perm.name == "setSecurityManager") {
            if (!allowSwitchBack.value) {
                throw SecurityException("SecurityManager change is not allowed")
            }
            return
        }

        delegate?.checkPermission(perm)
    }

    private var rootGroup: ThreadGroup? = null

    override fun getThreadGroup(): ThreadGroup? {
        if (rootGroup == null) {
            rootGroup = findRootGroup()
        }
        return rootGroup
    }

    private fun findRootGroup(): ThreadGroup {
        var root = Thread.currentThread().threadGroup
        while (root.parent != null) {
            root = root.parent
        }
        return root
    }
}

