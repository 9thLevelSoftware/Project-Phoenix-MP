package com.devil.phoenixproject.portal.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.devil.phoenixproject.portal.db.Users
import com.devil.phoenixproject.portal.models.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class AuthService {
    private val jwtSecret = System.getenv("JWT_SECRET") ?: "dev-secret-change-in-production"
    private val jwtIssuer = "phoenix-portal"
    private val jwtAudience = "phoenix-portal-users"

    fun generateToken(userId: UUID): String {
        return JWT.create()
            .withIssuer(jwtIssuer)
            .withAudience(jwtAudience)
            .withSubject(userId.toString())
            .withExpiresAt(Date(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)) // 7 days
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    fun verifyToken(token: String): UUID? {
        return try {
            val verifier = JWT.require(Algorithm.HMAC256(jwtSecret))
                .withIssuer(jwtIssuer)
                .withAudience(jwtAudience)
                .build()
            val decoded = verifier.verify(token)
            UUID.fromString(decoded.subject)
        } catch (e: Exception) {
            null
        }
    }

    fun extractUserId(call: ApplicationCall): UUID? {
        val authHeader = call.request.header("Authorization") ?: return null
        if (!authHeader.startsWith("Bearer ")) return null
        val token = authHeader.removePrefix("Bearer ")
        return verifyToken(token)
    }

    fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }

    fun verifyPassword(password: String, hash: String): Boolean {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
    }

    fun signup(request: SignupRequest): Result<AuthResponse> = transaction {
        // Check if email exists
        val existing = Users.selectAll().where { Users.email eq request.email }.firstOrNull()
        if (existing != null) {
            return@transaction Result.failure(Exception("Email already registered"))
        }

        if (request.password.length < 8) {
            return@transaction Result.failure(Exception("Password must be at least 8 characters"))
        }

        val userId = UUID.randomUUID()
        val now = Clock.System.now()

        Users.insert {
            it[id] = userId
            it[email] = request.email.lowercase()
            it[passwordHash] = hashPassword(request.password)
            it[displayName] = request.displayName
            it[createdAt] = now
        }

        val token = generateToken(userId)
        Result.success(AuthResponse(
            token = token,
            user = UserResponse(
                id = userId.toString(),
                email = request.email.lowercase(),
                displayName = request.displayName,
                isPremium = false
            )
        ))
    }

    fun login(request: LoginRequest): Result<AuthResponse> = transaction {
        val user = Users.selectAll().where { Users.email eq request.email.lowercase() }.firstOrNull()
            ?: return@transaction Result.failure(Exception("Invalid email or password"))

        if (!verifyPassword(request.password, user[Users.passwordHash])) {
            return@transaction Result.failure(Exception("Invalid email or password"))
        }

        val userId = user[Users.id].value
        val now = Clock.System.now()

        Users.update({ Users.id eq userId }) {
            it[lastLoginAt] = now
        }

        val token = generateToken(userId)
        Result.success(AuthResponse(
            token = token,
            user = UserResponse(
                id = userId.toString(),
                email = user[Users.email],
                displayName = user[Users.displayName],
                isPremium = user[Users.isPremium]
            )
        ))
    }

    fun getUser(userId: UUID): UserResponse? = transaction {
        Users.selectAll().where { Users.id eq userId }.firstOrNull()?.let { user ->
            UserResponse(
                id = user[Users.id].value.toString(),
                email = user[Users.email],
                displayName = user[Users.displayName],
                isPremium = user[Users.isPremium]
            )
        }
    }
}
