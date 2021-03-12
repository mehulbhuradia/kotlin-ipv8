package nl.tudelft.ipv8.attestation.tokenTree

import mu.KotlinLogging
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.keyvault.PublicKey
import nl.tudelft.ipv8.util.ByteArrayKey
import nl.tudelft.ipv8.util.sha3_256

const val UNCHAINED_MAX_SIZE = 100
const val DEFAULT_CHUNK_SUZE = 64

private val logger = KotlinLogging.logger {}

class TokenTree(publicKey: PublicKey? = null, privateKey: PrivateKey? = null) {

    val elements = hashMapOf<ByteArrayKey, Token>()
    val unchained = mutableMapOf<Token, ByteArray?>()

    var publicKey: PublicKey
    var privateKey: PrivateKey?
    var genesisHash: ByteArray

    init {
        if (publicKey != null && privateKey == null) {
            this.publicKey = publicKey.pub()
            this.privateKey = null
        } else if (publicKey == null && privateKey != null) {
            this.privateKey = privateKey
            this.publicKey = privateKey.pub()
        } else {
            throw RuntimeException("Both public key and private key are null.")
        }
        this.genesisHash = sha3_256(this.publicKey.keyToBin())
    }

    fun add(content: ByteArray, after: Token? = null): Token {
        if (privateKey == null) {
            throw RuntimeException("Attempted to create a token without a private key.")
        }
        val previousHash = after?.hash ?: this.genesisHash
        return this.append(Token(previousHash, content = content, privateKey = this.privateKey))
    }

    fun addByHash(contentHash: ByteArray, after: Token? = null): Token {
        if (privateKey == null) {
            throw RuntimeException("Attempted to create a token without a private key.")
        }
        val previousHash = after?.hash ?: this.genesisHash
        return this.append(Token(previousHash, contentHash = contentHash, privateKey = this.privateKey))
    }

    fun gatherToken(token: Token): Token? {
        if (token.verify(this.publicKey)) {
            if (!token.previousTokenHash.contentEquals(this.genesisHash) && !this.elements.containsKey(ByteArrayKey(
                    token.previousTokenHash))
            ) {
                this.unchained[token] = null
                if (this.unchained.size > UNCHAINED_MAX_SIZE) {
                    this.unchained.remove(this.unchained.keys.last())
                }
                logger.info("Delaying unchained token $token!")
                return null
            } else if (this.elements.containsKey(ByteArrayKey(token.hash))) {
                val shadowToken = this.elements[ByteArrayKey(token.hash)]!!
                if (shadowToken.content == null && token.content != null) {
                    shadowToken.receiveContent(token.content!!)
                }
                return shadowToken
            } else {
                this.appendChainReactionToken(token)
            }
            return token
        }
        return null
    }

    fun getMissing(): Set<ByteArray> {
        return this.unchained.keys.map { it.previousTokenHash }.toSet()
    }

    fun verify(token: Token, maxDepth: Int = 1000): Boolean {
        var current = token
        var steps = 0
        while (maxDepth == -1 || maxDepth > steps) {
            if (!current.verify(this.publicKey)) {
                return false
            }
            if (current.previousTokenHash.contentEquals(this.genesisHash)) {
                return false
            }
            if (!this.elements.containsKey(ByteArrayKey(current.previousTokenHash))) {
                return false
            }
            current = this.elements[ByteArrayKey(current.previousTokenHash)]!!
            steps += 1
        }
        return steps < maxDepth
    }

    fun getRootPath(token: Token, maxDepth: Int = 1000): List<Token> {
        var current = token
        var steps = 0
        val path = arrayListOf(token)
        while (maxDepth == -1 || maxDepth > steps) {
            if (!current.verify(this.publicKey)) {
                return arrayListOf()
            }
            if (current.previousTokenHash.contentEquals(this.genesisHash)) {
                break
            }
            if (!this.elements.containsKey(ByteArrayKey(current.previousTokenHash))) {
                return arrayListOf()
            }
            current = this.elements[ByteArrayKey(current.previousTokenHash)]!!
            path.add(current)
            steps += 1
        }
        return if (steps < maxDepth) {
            path
        } else {
            arrayListOf()
        }
    }

    fun serializePublic(upTo: Token? = null): ByteArray {
        return if (upTo != null) {
            var out = upTo.getPlaintextSigned()
            var nextTokenHash = upTo.previousTokenHash
            var token: Token
            while (this.elements.contains(ByteArrayKey(nextTokenHash))) {
                token = this.elements[ByteArrayKey(nextTokenHash)]!!
                out += token.getPlaintextSigned()
                nextTokenHash = token.previousTokenHash
            }
            out
        } else {
            var out = byteArrayOf()
            this.elements.values.forEach { out += it.getPlaintextSigned() }
            out
        }
    }

    fun deserializePublic(serialized: ByteArray): Boolean {
        val signatureLength = this.publicKey.getSignatureLength()
        val chunkSize = DEFAULT_CHUNK_SUZE + signatureLength
        var isCorrect = true
        for (i in serialized.indices step chunkSize) {
            isCorrect = isCorrect && this.gatherToken(Token.deserialize(serialized, this.publicKey, offset = i)) != null
        }
        return isCorrect
    }

    private fun append(token: Token): Token {
        this.elements[ByteArrayKey(token.hash)] = token
        return token
    }

    private fun appendChainReactionToken(token: Token) {
        this.append(token)
        var retryToken: Token? = null
        for (lostToken in this.unchained.keys) {
            if (lostToken.previousTokenHash.contentEquals(token.hash)) {
                retryToken = lostToken
                break
            }
        }
        if (retryToken != null) {
            this.unchained.remove(retryToken)
            if (this.gatherToken(retryToken) != null) {
                logger.warn { "Dropped illegal token $retryToken!" }
            }
        }
    }

}
