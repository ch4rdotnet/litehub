package com.chardidathing.litehub.source.ha

import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.source.ha.FakeHa.Companion.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HaClientTest {

    private val ha = FakeHa(token = "good")
    private val confined = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val statuses = LinkedBlockingQueue<HaClient.Status>()
    private val events = LinkedBlockingQueue<HaClient.EntityEvent>()
    private val entities = HashMap<String, Entity>()

    private val listener = object : HaClient.Listener {
        override fun onStatus(status: HaClient.Status) {
            statuses += status
        }

        override fun onEntities(event: HaClient.EntityEvent) {
            EntityDiff.apply(entities, event.body)
            events += event
        }
    }

    private val timing = HaTiming(backoffMin = 50.milliseconds, backoffMax = 200.milliseconds, commandTimeout = 2.seconds)

    private fun client(token: String = "good") =
        HaClient(HaCredentials(ha.url, token), OkHttpClient(), scope, timing, listener)

    @After
    fun tearDown() {
        scope.cancel()
        ha.close()
    }

    @Test
    fun `subscribes to the wanted entities and gets their state`() {
        ha.set("light.kitchen", "on")
        ha.accept()
        val client = client()
        runBlocking(confined) { client.setEntityIds(setOf("light.kitchen", "light.gone")) }
        client.start()
        awaitStatus<HaClient.Status.Connected>()
        val first = events.poll(5, TimeUnit.SECONDS)!!
        assertTrue(first.initial)
        assertEquals(setOf("light.kitchen", "light.gone"), first.requested)
        assertEquals("on", entities.getValue("light.kitchen").state)
        assertTrue("light.gone" !in entities)
    }

    @Test
    fun `applies changes`() {
        ha.set("switch.fan", "off")
        ha.accept()
        val client = client()
        runBlocking(confined) { client.setEntityIds(setOf("switch.fan")) }
        client.start()
        events.poll(5, TimeUnit.SECONDS)!!
        ha.change("switch.fan", "on")
        val next = events.poll(5, TimeUnit.SECONDS)!!
        assertTrue(!next.initial)
        assertEquals("on", entities.getValue("switch.fan").state)
        assertEquals("switch.fan", entities.getValue("switch.fan").attribute("friendly_name"))
    }

    @Test
    fun `reports a rejected token`() {
        ha.accept()
        client(token = "bad").start()
        val failed = awaitStatus<HaClient.Status.Failed>()
        assertEquals("token rejected", failed.reason)
    }

    @Test
    fun `reconnects and resubscribes after ha restarts`() {
        ha.set("light.hall", "off")
        ha.accept()
        ha.accept()
        val client = client()
        runBlocking(confined) { client.setEntityIds(setOf("light.hall")) }
        client.start()
        awaitStatus<HaClient.Status.Connected>()
        events.poll(5, TimeUnit.SECONDS)!!
        ha.restart()
        awaitStatus<HaClient.Status.Failed>()
        awaitStatus<HaClient.Status.Connected>()
        val again = events.poll(5, TimeUnit.SECONDS)!!
        assertTrue(again.initial)
    }

    @Test
    fun `swaps subscriptions without a gap`() {
        ha.accept()
        val client = client()
        client.start()
        awaitStatus<HaClient.Status.Connected>()
        runBlocking(confined) { client.setEntityIds(setOf("light.a")) }
        runBlocking(confined) { client.setEntityIds(setOf("light.b")) }
        val types = generateSequence { ha.received.poll(5, TimeUnit.SECONDS) }
            .map { it.type() }.take(4).toList()
        assertEquals(listOf("auth", "subscribe_entities", "subscribe_entities", "unsubscribe_events"), types)
    }

    @Test
    fun `service calls report ha's error`() {
        ha.accept()
        val client = client()
        client.start()
        awaitStatus<HaClient.Status.Connected>()
        val ok = runBlocking { withContext(confined) { client.callService("light", "toggle", "light.a") } }
        assertTrue(ok.isSuccess)
        ha.failServices = true
        val failed = runBlocking { withContext(confined) { client.callService("light", "toggle", "light.a") } }
        assertEquals("service not found.", failed.exceptionOrNull()?.message)
    }

    private inline fun <reified T : HaClient.Status> awaitStatus(): T {
        while (true) {
            val s = statuses.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no ${T::class.simpleName} status")
            if (s is T) return s
        }
    }
}
