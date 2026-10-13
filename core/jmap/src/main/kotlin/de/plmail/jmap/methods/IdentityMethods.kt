package de.plmail.jmap.methods

import de.plmail.jmap.mail.Identity
import de.plmail.jmap.protocol.AccountId
import de.plmail.jmap.protocol.IdentityId
import de.plmail.jmap.protocol.JmapMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `Identity/get` — the addresses this account may send from, primary first.
 *
 * Excluded from push notifications on purpose: identities change only when the user edits their own
 * addresses, which they just did in the app.
 */
class IdentityGet(private val accountId: AccountId) : JmapMethod<IdentityGetResult> {

    override val name = "Identity/get"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("ids", JsonNull)
    }

    override fun decode(json: Json, arguments: JsonObject): IdentityGetResult =
        json.decodeFromJsonElement(IdentityGetResult.serializer(), arguments)
}

@Serializable
data class IdentityGetResult(
    val accountId: String = "",
    val state: String = "",
    val list: List<Identity> = emptyList(),
    val notFound: List<IdentityId> = emptyList(),
) {
    /** The default From. The server already orders primary first. */
    val primary: Identity?
        get() = list.firstOrNull()
}

/**
 * `Identity/set` create — adds a sending address (an alias) to the account.
 *
 * BearerMail: on a self-hosted mail server the new alias is also written to Postfix's alias map by
 * the server (MailServerAliasSync), so the address starts *receiving* within seconds, not only
 * sending. That is what makes an on-the-spot alias for a shop counter or a stranger useful.
 */
class IdentityCreate(
    private val accountId: AccountId,
    private val email: String,
    private val displayName: String? = null,
) : JmapMethod<IdentitySetResult> {

    override val name = "Identity/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put(
            "create",
            buildJsonObject {
                put(
                    CREATION_ID,
                    buildJsonObject {
                        put("email", email)
                        displayName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                    },
                )
            },
        )
    }

    override fun decode(json: Json, arguments: JsonObject): IdentitySetResult =
        json.decodeFromJsonElement(IdentitySetResult.serializer(), arguments)

    companion object {
        const val CREATION_ID = "new"
    }
}

/** `Identity/destroy` — removes an alias this client (or the web UI) created by hand. */
class IdentityDestroy(private val accountId: AccountId, private val id: IdentityId) :
    JmapMethod<IdentitySetResult> {

    override val name = "Identity/set"

    override fun arguments(): JsonObject = buildJsonObject {
        put("accountId", accountId.value)
        put("destroy", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(id.value)) })
    }

    override fun decode(json: Json, arguments: JsonObject): IdentitySetResult =
        json.decodeFromJsonElement(IdentitySetResult.serializer(), arguments)
}

@Serializable data class CreatedIdentity(val id: IdentityId, val mayDelete: Boolean = true)

@Serializable
data class IdentitySetResult(
    val accountId: String = "",
    val newState: String = "",
    val created: Map<String, CreatedIdentity> = emptyMap(),
    val notCreated: Map<String, SetError> = emptyMap(),
    val destroyed: List<IdentityId> = emptyList(),
    val notDestroyed: Map<String, SetError> = emptyMap(),
)
