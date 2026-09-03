/**
 * local cache -> query with PartnerCacheClient -> Hypixel API
 */
suspend fun getProfile(uuid: String): Result<CachedProfileData?> {
    val rawResult = RedisService.getOrCompute("profile:$uuid", HypixelRouter.profileCacheTime) {
        if (PartnerCacheClient.isConnected()) {
            val partnerEntry = PartnerCacheClient.queryUuid(uuid)
            if (partnerEntry != null) return@getOrCompute partnerEntry.data
        }

        if (HypixelAPI.isRateLimited()) throw HypixelException.RateLimited()

        val response = HypixelAPI.fetchProfile(uuid) ?: throw HypixelException.ApiUnavailable("Hypixel API returned no data")
        response.bodyAsText()
    }

    return rawResult.map { raw -> raw?.let { extractProfileData(uuid, it) } }
}