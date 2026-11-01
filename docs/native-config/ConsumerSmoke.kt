package nativeconfig.smoke

import com.dsha.sidecar.net.config.native.ConfigDocuments
import com.dsha.sidecar.net.config.native.ConfigUpdate
import com.dsha.sidecar.net.config.native.NativeConfigRepository
import com.dsha.sidecar.net.dns.DnsProviderConfig
import com.dsha.sidecar.net.rules.native.RulePlanner
import com.dsha.sidecar.net.runtime.NativeRuntimeConfig

/**
 * Test-only: compiles against the actual sibling consumer source, not stubs.
 * Does not start the proxy, DNS transport, certificate authority or any socket.
 */
object ConsumerSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val repository = NativeConfigRepository(validate = { NativeRuntimeConfig.decode(it.configuration) })
        val result = repository.reload(ConfigDocuments(
            """{
              server:{
                setting:{verifySsl:true,script:{enabled:false}},
                intercepts:{
                  'service.example.test':{
                    '^/old':{redirect:'https://target.example.test/new',order:1},
                  },
                },
                dns:{
                  providers:{fixture:{server:'udp://192.0.2.53:53'}},
                  mapping:{'service.example.test':'fixture'},
                  ech:{enabled:false},
                  nat64:{enabled:false},
                },
              },
            }""", "{}",
        ))
        check(result is ConfigUpdate.Applied)
        val snapshot = result.snapshot
        val planner = RulePlanner(listOf(snapshot.configuration))
        val plan = planner.plan("service.example.test", "/old?q=1").requireSupported()
        check(plan.localResponse?.status == 302)
        check(plan.localResponse?.headers?.get("Location") == "https://target.example.test/new")
        val catalog = DnsProviderConfig.normalize(
            snapshot.dns["providers"] as Map<String, Any?>, snapshot.dns,
        )
        check(catalog.providerKey("fixture") == "provider:fixture")
        check(catalog.descriptors.first().server == "192.0.2.53")
        val runtime = NativeRuntimeConfig.decode(snapshot.configuration)
        check(!runtime.requiresEchAdapter)
        // Consumer validation failure must retain the prior repository epoch.
        val invalid = repository.reload(ConfigDocuments("{server:{dns:{unboundFixtureSetting:true}}}", "{}"))
        check(invalid is ConfigUpdate.Rejected)
        check(repository.current() === snapshot)
        println("Real RulePlanner redirect + DNS catalog + NativeRuntimeConfig decode/rollback passed.")
    }
}
