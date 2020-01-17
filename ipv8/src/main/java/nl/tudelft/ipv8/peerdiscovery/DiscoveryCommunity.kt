package nl.tudelft.ipv8.peerdiscovery

import android.util.Log
import nl.tudelft.ipv8.Address
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.payload.*
import nl.tudelft.ipv8.peerdiscovery.payload.PingPayload
import nl.tudelft.ipv8.peerdiscovery.payload.PongPayload
import nl.tudelft.ipv8.peerdiscovery.payload.SimilarityRequestPayload
import nl.tudelft.ipv8.peerdiscovery.payload.SimilarityResponsePayload
import java.util.*

class DiscoveryCommunity(
    myPeer: Peer,
    endpoint: Endpoint,
    network: Network
) : Community(myPeer, endpoint, network), PingOverlay {
    override val serviceId = "7e313685c1912a141279f8248fc8db5899c5df5a"

    private val pingRequestCache: MutableMap<Int, PingRequest> = mutableMapOf()

    init {
        messageHandlers[MessageId.SIMILARITY_REQUEST] = ::handleSimilarityRequest
        messageHandlers[MessageId.SIMILARITY_RESPONSE] = ::handleSimilarityResponse
        messageHandlers[MessageId.PING] = ::handlePing
        messageHandlers[MessageId.PONG] = ::handlePong
    }

    /*
     * Request creation
     */

    internal fun createSimilarityRequest(peer: Peer): ByteArray {
        val globalTime = claimGlobalTime()
        val payload = SimilarityRequestPayload(
            (globalTime % 65536u).toInt(),
            myEstimatedLan,
            myEstimatedWan,
            ConnectionType.UNKNOWN,
            getMyOverlays(peer)
        )
        val auth = BinMemberAuthenticationPayload(peer.publicKey.keyToBin())
        val dist = GlobalTimeDistributionPayload(globalTime)
        Log.d("DiscoveryCommunity", "-> $payload")
        return serializePacket(prefix, MessageId.SIMILARITY_REQUEST, listOf(auth, dist, payload))
    }

    fun sendSimilarityRequest(address: Address) {
        val myPeerSet = network.serviceOverlays.values.map { it.myPeer }.toSet()
        for (myPeer in myPeerSet) {
            val packet = createSimilarityRequest(myPeer)
            send(address, packet)
        }
    }

    internal fun createSimilarityResponse(identifier: Int, peer: Peer): ByteArray {
        val globalTime = claimGlobalTime()
        val payload = SimilarityResponsePayload(identifier, getMyOverlays(peer))
        val auth = BinMemberAuthenticationPayload(peer.publicKey.keyToBin())
        val dist = GlobalTimeDistributionPayload(globalTime)
        Log.d("DiscoveryCommunity", "-> $payload")
        return serializePacket(prefix, MessageId.SIMILARITY_RESPONSE, listOf(auth, dist, payload))
    }

    override fun sendPing(peer: Peer) {
        val globalTime = claimGlobalTime()
        val payload = PingPayload((globalTime % 65536u).toInt())
        val dist = GlobalTimeDistributionPayload(globalTime)
        Log.d("DiscoveryCommunity", "-> $payload")
        val packet = serializePacket(prefix, MessageId.PING, listOf(dist, payload), sign = false)

        val pingRequest = PingRequest(payload.identifier, peer, Date())
        pingRequestCache[payload.identifier] = pingRequest
        // TODO: implement cache timeout

        send(peer.address, packet)
    }

    internal fun createPong(identifier: Int): ByteArray {
        val globalTime = claimGlobalTime()
        val payload = PongPayload(identifier)
        val dist = GlobalTimeDistributionPayload(globalTime)
        Log.d("DiscoveryCommunity", "-> $payload")
        return serializePacket(prefix, MessageId.PONG, listOf(dist, payload), sign = false)
    }

    /*
     * Request deserialization
     */

    internal fun handleSimilarityRequest(packet: Packet) {
        val (peer, remainder) = unwrapAuthPacket(packet)
        val (_, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = SimilarityRequestPayload.deserialize(remainder, distSize)
        onSimilarityRequest(peer, payload)
    }

    internal fun handleSimilarityResponse(packet: Packet) {
        val (peer, remainder) = unwrapAuthPacket(packet)
        val (_, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = SimilarityResponsePayload.deserialize(remainder, distSize)
        onSimilarityResponse(peer, payload)
    }

    internal fun handlePing(packet: Packet) {
        val remainder = unwrapUnauthPacket(packet)
        val (dist, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = PingPayload.deserialize(remainder, distSize)
        onPing(packet.source, dist, payload)
    }

    internal fun handlePong(packet: Packet) {
        val remainder = unwrapUnauthPacket(packet)
        val (dist, distSize) = GlobalTimeDistributionPayload.deserialize(remainder)
        val (payload, _) = PongPayload.deserialize(remainder, distSize)
        onPong(dist, payload)
    }

    /*
     * Request handling
     */

    override fun onIntroductionResponse(
        peer: Peer,
        dist: GlobalTimeDistributionPayload,
        payload: IntroductionResponsePayload
    ) {
        super.onIntroductionResponse(peer, dist, payload)
        sendSimilarityRequest(peer.address)
    }

    internal fun onSimilarityRequest(
        peer: Peer,
        payload: SimilarityRequestPayload
    ) {
        Log.d("DiscoveryCommunity", "<- $payload")

        network.discoverServices(peer, payload.preferenceList)

        val myPeerSet = network.serviceOverlays.values.map { it.myPeer }.toSet()
        for (myPeer in myPeerSet) {
            val packet = createSimilarityResponse(payload.identifier, myPeer)
            send(peer.address, packet)
        }
    }

    internal fun onSimilarityResponse(
        peer: Peer,
        payload: SimilarityResponsePayload
    ) {
        Log.d("DiscoveryCommunity", "<- $payload")

        if (maxPeers >= 0 && getPeers().size >= maxPeers && !network.verifiedPeers.contains(peer)) {
            Log.i("DiscoveryCommunity", "Dropping similarity response from $peer, too many peers!")
            return
        }

        network.addVerifiedPeer(peer)
        network.discoverServices(peer, payload.preferenceList)
    }

    internal fun onPing(
        address: Address,
        dist: GlobalTimeDistributionPayload,
        payload: PingPayload
    ) {
        Log.d("DiscoveryCommunity", "<- $payload")
        Log.d("DiscoveryCommunity", "dist = $dist")

        val packet = createPong(payload.identifier)
        send(address, packet)
    }

    internal fun onPong(
        dist: GlobalTimeDistributionPayload,
        payload: PongPayload
    ) {
        Log.d("DiscoveryCommunity", "<- $payload")
        Log.d("DiscoveryCommunity", "dist = $dist")

        val pingRequest = pingRequestCache[payload.identifier]
        if (pingRequest != null) {
            pingRequest.peer.addPing((Date().time - pingRequest.startTime.time) / 1000.0)
            pingRequestCache.remove(payload.identifier)
        }
    }

    private fun getMyOverlays(peer: Peer): List<String> {
        return network.serviceOverlays
            .filter { it.value.myPeer == peer }
            .map { it.key }
    }

    object MessageId {
        const val SIMILARITY_REQUEST = 1
        const val SIMILARITY_RESPONSE = 2
        const val PING = 3
        const val PONG = 4
    }

    class PingRequest(val identifier: Int, val peer: Peer, val startTime: Date)
}