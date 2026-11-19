package io.github.cinpecan.engram.data.remote

import java.io.IOException

/**
 * Explicit HTTP status failure returned by the Engram cloud gateway so callers
 * can distinguish auth/quota responses (e.g. 401/429) from connectivity errors.
 */
class CloudGatewayHttpException(
    val statusCode: Int,
) : IOException("Cloud gateway HTTP $statusCode")
