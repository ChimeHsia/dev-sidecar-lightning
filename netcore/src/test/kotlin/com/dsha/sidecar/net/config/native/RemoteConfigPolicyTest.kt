package com.dsha.sidecar.net.config.native

import org.junit.Assert.*
import org.junit.Test

class RemoteConfigPolicyTest {
    private val bootstrap = RemoteFetchDefaults(
        "https://resolver.example.test/dns-query", "bootstrap", "resolver.example.test",
    )
    private fun repository() = NativeConfigRepository(remoteFetchDefaults = bootstrap)

    @Test fun httpRewriteMatchesUpstreamOnceAndPreservesExplicitPortAndEscapes() {
        val first = RemoteConfigPolicy.address(RemoteChannel.SHARED, " \tHTTP://rules.example.test:80/a%2Fb?q=%26 \r")!!
        assertTrue(first.upgradedFromHttp)
        assertEquals("https://rules.example.test:80/a%2Fb?q=%26", first.uri.toString())
        val second = RemoteConfigPolicy.address(RemoteChannel.SHARED, first.uri.toString())!!
        assertFalse(second.upgradedFromHttp)
        assertEquals(first.uri, second.uri)
    }

    @Test fun alreadyHttpsCaseIsUnchangedButAmbiguousOrUnsafeNativeUrlsReject() {
        val input = "HTTPS://RULES.example.test:8443/a"
        assertEquals(input, RemoteConfigPolicy.address(RemoteChannel.PERSONAL, input)!!.uri.toString())
        assertNull(RemoteConfigPolicy.address(RemoteChannel.SHARED, ""))
        assertNull(RemoteConfigPolicy.address(RemoteChannel.SHARED, null))
        for (bad in listOf(
            " https://rules.example.test/a", "https://rules.example.test/a ",
            "ftp://rules.example.test/a", "file:///config", "//rules.example.test",
            "https://fixture-user@rules.example.test/a", "https://rules.example.test/a#fragment",
            "https://rules.example.test:0/a", "https://rules.example.test:65536/a",
            "https://rules.example.test:/a", "https://rules.example.test/%bad%zz",
            "https://rules.example.test\\@other.test", "https:///missing", "https://\u4e2d.test/a",
        )) assertEquals(bad, ConfigError.URL,
            rejected { RemoteConfigPolicy.address(RemoteChannel.SHARED, bad) }.code)
    }

    @Test fun normalizationChangesDecodedSnapshotWithoutWritingFiles() {
        val snapshot = applied(repository().reload(ConfigDocuments(
            "{app:{remoteConfig:{enabled:false,url:'http://rules.example.test/a'}}}", "{}",
        )))
        val remote = (snapshot.configuration["app"] as Map<*, *>)["remoteConfig"] as Map<*, *>
        assertEquals("https://rules.example.test/a", remote["url"])
        assertTrue(snapshot.remoteAddresses.single().upgradedFromHttp)
    }

    @Test fun fetchBootstrapUsesExistingProviderAndActualMappingOverwriteSemantics() {
        val snapshot = applied(repository().reload(ConfigDocuments(
            """{
              app:{remoteConfig:{enabled:true,url:'http://rules.example.test/a',
                fetchRule:{sni:'edge.example.test'}}},
              server:{
                dns:{providers:{existing:{server:'https://resolver.example.test/custom'}},
                  mapping:{'rules.example.test':'user-provider'}},
                intercepts:{'rules.example.test':{'.*':{sni:'user.example.test'}}},
                preSetIpList:{'rules.example.test':{'192.0.2.7':true}},
              },
            }""", "{}",
        )))
        assertEquals("existing", (snapshot.dns["mapping"] as Map<*, *>)["rules.example.test"])
        assertEquals(1, (snapshot.dns["providers"] as Map<*, *>).size)
        assertEquals("user.example.test",
            ((snapshot.intercepts["rules.example.test"] as Map<*, *>)[".*"] as Map<*, *>)["sni"])
        assertEquals(mapOf("192.0.2.7" to true),
            (snapshot.server["preSetIpList"] as Map<*, *>)["rules.example.test"])
    }

    @Test fun bootstrapFillsMissingProviderInterceptAndCallerSuppliedPreset() {
        val repository = NativeConfigRepository(remoteFetchDefaults = bootstrap.copy(
            presetIpLists = mapOf("rules.example.test" to mapOf("192.0.2.9" to true)),
        ))
        val snapshot = applied(repository.reload(ConfigDocuments(
            """{app:{remoteConfig:{enabled:true,url:'https://rules.example.test/a',
             fetchRule:{dns:'custom',sni:'edge.example.test'}}}}""", "{}",
        )))
        assertEquals(mapOf("server" to bootstrap.dohUrl), (snapshot.dns["providers"] as Map<*, *>)["custom"])
        assertEquals("edge.example.test",
            ((snapshot.intercepts["rules.example.test"] as Map<*, *>)[".*"] as Map<*, *>)["sni"])
        assertEquals(mapOf("192.0.2.9" to true), (snapshot.server["preSetIpList"] as Map<*, *>)["rules.example.test"])
    }

    @Test fun callerBootstrapMapsAndNumberObjectsCannotMutatePublishedSnapshots() {
        val flag = java.util.concurrent.atomic.AtomicInteger(1)
        val presets = linkedMapOf<String, Any?>("rules.example.test" to mapOf("192.0.2.9" to flag))
        val repository = NativeConfigRepository(remoteFetchDefaults = bootstrap.copy(presetIpLists = presets))
        flag.set(2)
        presets.clear()
        val snapshot = applied(repository.reload(ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'https://rules.example.test'}}}", "{}",
        )))
        assertEquals(1.0, ((snapshot.server["preSetIpList"] as Map<*, *>)["rules.example.test"] as Map<*, *>)["192.0.2.9"])
    }

    @Test fun enabledFetchRulesRequireExplicitBootstrapRatherThanInventingRealEndpoints() {
        assertEquals(ConfigError.VALIDATION, (NativeConfigRepository().reload(ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'https://rules.example.test'}}}", "{}",
        )) as ConfigUpdate.Rejected).error)
    }

    @Test fun newServerKeyOrderIsReservedBeforeAddingBootstrapSiblings() {
        val snapshot = applied(repository().reload(ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'https://rules.example.test'}}}", "{}",
        )))
        assertEquals(listOf("dns", "intercepts", "preSetIpList"), snapshot.server.keys.toList())
    }

    @Test fun insecureBootstrapDohDefaultsCannotSlipThroughTheRemoteUrlRewrite() {
        val repository = NativeConfigRepository(remoteFetchDefaults = bootstrap.copy(
            dohUrl = "http://resolver.example.test/dns-query",
        ))
        assertEquals(ConfigError.URL, (repository.reload(ConfigDocuments(
            "{app:{remoteConfig:{enabled:true}}}", "{}",
        )) as ConfigUpdate.Rejected).error)
    }

    @Test fun fetcherOnlyRunsExplicitlyAndReceivesHttpsAndTheRealByteCap() {
        val repository = repository()
        val documents = ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'http://shared.example.test',personalUrl:'http://personal.example.test'}},server:{port:1}}",
            "{}",
        )
        val before = applied(repository.reload(documents))
        val channels = mutableListOf<RemoteChannel>()
        val result = repository.reloadWithFetcher(documents) { address, maximum ->
            assertEquals("https", address.uri.scheme)
            assertEquals(1_048_576, maximum)
            channels += address.channel
            if (address.channel == RemoteChannel.SHARED) "{server:{port:2}}" .toByteArray()
            else "{server:{port:3}}" .toByteArray()
        }
        assertEquals(listOf(RemoteChannel.SHARED, RemoteChannel.PERSONAL), channels)
        val after = applied(result)
        assertEquals(3.0, after.server["port"])
        assertEquals(before.revision + 1, after.revision)
    }

    @Test fun failedSecondResponseOrRetargetingPreservesWholePreviousEpoch() {
        val repository = repository()
        val documents = ConfigDocuments(
            "{app:{remoteConfig:{enabled:true,url:'https://shared.example.test',personalUrl:'https://personal.example.test'}}}",
            "{}",
        )
        val before = applied(repository.reload(documents))
        val failed = repository.reloadWithFetcher(documents) { request, _ ->
            if (request.channel == RemoteChannel.PERSONAL) throw IllegalStateException("private-token")
            "{server:{changed:true}}".toByteArray()
        } as ConfigUpdate.Rejected
        assertSame(before, failed.previous)
        assertEquals(ConfigError.FETCH, failed.error)
        assertFalse(failed.toString().contains("private-token"))
        val retargeted = repository.reloadWithFetcher(documents) { _, _ ->
            "{app:{remoteConfig:{url:'https://replacement.example.test'}}}".toByteArray()
        } as ConfigUpdate.Rejected
        assertEquals(ConfigError.URL, retargeted.error)
        assertSame(before, repository.current())
    }

    @Test fun oversizedOrInvalidFetcherBodyNeverPublishesPartialConfiguration() {
        val repository = repository()
        val documents = ConfigDocuments("{app:{remoteConfig:{enabled:true,url:'https://shared.example.test'}}}", "{}")
        val before = applied(repository.reload(documents))
        for (body in listOf("bad".toByteArray(), ByteArray(1_048_577))) {
            assertTrue(repository.reloadWithFetcher(documents) { _, _ -> body } is ConfigUpdate.Rejected)
            assertSame(before, repository.current())
        }
    }

    @Test fun disabledRemoteDoesNotCallTheFetcher() {
        val repository = repository()
        val result = repository.reloadWithFetcher(ConfigDocuments("{}", "{}")) { _, _ ->
            throw AssertionError("Disabled fetcher was called")
        }
        assertTrue(result is ConfigUpdate.Applied)
    }
}
