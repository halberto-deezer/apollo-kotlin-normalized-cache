package test

import app.cash.turbine.test
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CompiledArgumentDefinition
import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.toResponseJson
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.CacheMissException
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.api.CacheControlCacheResolver
import com.apollographql.cache.normalized.api.CacheKey
import com.apollographql.cache.normalized.api.CacheKeyGenerator
import com.apollographql.cache.normalized.api.CacheKeyGeneratorContext
import com.apollographql.cache.normalized.api.DefaultCacheResolver
import com.apollographql.cache.normalized.api.FieldPolicies
import com.apollographql.cache.normalized.api.GlobalMaxAgeProvider
import com.apollographql.cache.normalized.api.IdCacheKeyGenerator
import com.apollographql.cache.normalized.api.KeyArgumentsCacheResolver
import com.apollographql.cache.normalized.api.KeyArgumentsProvider
import com.apollographql.cache.normalized.apolloStore
import com.apollographql.cache.normalized.cacheInfo
import com.apollographql.cache.normalized.fetchPolicy
import com.apollographql.cache.normalized.isFromCache
import com.apollographql.cache.normalized.maxStale
import com.apollographql.cache.normalized.memory.MemoryCacheFactory
import com.apollographql.cache.normalized.normalizedCache
import com.apollographql.cache.normalized.options.cacheMissesAsException
import com.apollographql.cache.normalized.storeReceivedDate
import com.apollographql.mockserver.MockResponse
import com.apollographql.mockserver.MockServer
import declarative.GetUserNameQuery
import declarative.cache.Cache.cache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import programmatic.GetProductNameQuery
import programmatic.GetProductNameQuery.Product
import programmatic.GetProductNameWithoutParamQuery
import programmatic.GetProductNamesByIdsQuery
import programmatic.GetProductNamesByIdsQuery.ProductsById
import programmatic.type.Query
import kotlin.collections.orEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours


class StaleTest {
  @Test
  fun staleErrorThenNetworkError() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = DefaultCacheResolver,
            ),
            enableOptimisticUpdates = true,
        )
        .build()


    apolloClient.apolloStore.writeOperation(
        operation = GetUserNameQuery(),
        data = GetUserNameQuery.Data(
            GetUserNameQuery.User(
                __typename = "User",
                name = "John Doe",
            ),
        ),
        cacheHeaders = receivedDate(currentTimeSeconds() - 15.days.inWholeSeconds),
    )
    mockServer.enqueue(MockResponse.Builder().statusCode(500).body("error").build())

    val response: Flow<ApolloResponse<GetUserNameQuery.Data>> = apolloClient
        .query(GetUserNameQuery())
        .fetchPolicy(FetchPolicy.CacheFirst)
        .toFlow()

    response.test {
      awaitItem().let { item ->
        // First emission: cache error
        assertIs<ApolloResponse<GetUserNameQuery.Data>>(item)
        assertEquals(expected = true, actual = item.cacheInfo?.isStale)
        assertNull(item.data)
        assertNotNull(item.exception)
        assertIs<CacheMissException>(item.exception)
        assertFalse(item.isLast)
        assertEquals(expected = true, actual = item.isFromCache)
      }
      awaitItem().let { item ->
        // First emission: network error
        assertIs<ApolloResponse<GetUserNameQuery.Data>>(item)
        assertEquals(expected = false, actual = item.cacheInfo?.isStale)
        assertNull(item.data)
        assertNotNull(item.exception)
        assertIs<ApolloHttpException>(item.exception)
        assertTrue(item.isLast)
        assertEquals(expected = false, actual = item.isFromCache)
      }
      awaitComplete()
    }
  }

  @Test
  fun cacheSuccessAfterNetworkWithIds() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = ProductCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = KeyArgumentsCacheResolver(
                    keyArgumentsProvider = ProductKeyArgumentsProvider(),
                    keyScope = CacheKey.Scope.TYPE,
                ),
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    mockServer.enqueue(
        MockResponse.Builder()
            .body(
                GetProductNameQuery.Data(
                    product = Product(
                        id = "1",
                        name = "name",
                    )
                ).toResponseJson()
            )
            .build()
    )

    val networkResponse = apolloClient
        .query(GetProductNameQuery("1"))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    val cacheResponse = apolloClient
        .query(GetProductNameWithoutParamQuery())
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    networkResponse.let { item ->
      // First emission: network success
      assertIs<ApolloResponse<GetProductNameQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = false, actual = item.isFromCache)
    }
    cacheResponse.let { item ->
      // First emission: cache success
      assertIs<ApolloResponse<GetProductNameWithoutParamQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = true, actual = item.isFromCache)
    }
  }

  @Test
  fun cacheSuccessAfterNetworkWithIdsReversed() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = ProductCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = KeyArgumentsCacheResolver(
                    keyArgumentsProvider = ProductKeyArgumentsProvider(),
                    keyScope = CacheKey.Scope.TYPE,
                ),
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    mockServer.enqueue(
        MockResponse.Builder()
            .body(
                GetProductNameQuery.Data(
                    product = Product(
                        id = "1",
                        name = "name",
                    )
                ).toResponseJson()
            )
            .build()
    )

    val networkResponse = apolloClient
        .query(GetProductNameWithoutParamQuery())
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    val cacheResponse = apolloClient
        .query(GetProductNameQuery("1"))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    networkResponse.let { item ->
      // First emission: network success
      assertIs<ApolloResponse<GetProductNameWithoutParamQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = false, actual = item.isFromCache)
    }
    cacheResponse.let { item ->
      // First emission: cache success
      assertIs<ApolloResponse<GetProductNameQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = true, actual = item.isFromCache)
    }
  }

  @Test
  fun cacheSuccessAfterNetworkWithIds2() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = ProductCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = KeyArgumentsCacheResolver(
                    keyArgumentsProvider = ProductKeyArgumentsProvider(),
                    keyScope = CacheKey.Scope.TYPE,
                ),
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    mockServer.enqueue(
        MockResponse.Builder()
            .body(
                GetProductNameQuery.Data(
                    product = Product(
                        id = "1",
                        name = "name",
                    )
                ).toResponseJson()
            )
            .build()
    )

    val networkResponse = apolloClient
        .query(GetProductNameQuery("1"))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    val cacheResponse = apolloClient
        .query(GetProductNamesByIdsQuery(listOf("1")))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    networkResponse.let { item ->
      // First emission: network success
      assertIs<ApolloResponse<GetProductNameQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = false, actual = item.isFromCache)
    }
    cacheResponse.let { item ->
      // First emission: cache success
      assertIs<ApolloResponse<GetProductNamesByIdsQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = true, actual = item.isFromCache)
    }
  }

  @Test
  fun cacheSuccessAfterNetworkWithIds2Reversed() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = ProductCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = KeyArgumentsCacheResolver(
                    keyArgumentsProvider = ProductKeyArgumentsProvider(),
                    keyScope = CacheKey.Scope.TYPE,
                ),
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    mockServer.enqueue(
        MockResponse.Builder()
            .body(
                GetProductNamesByIdsQuery.Data(
                    productsByIds = listOf(
                        ProductsById(
                            id = "1",
                            name = "name",
                        )
                    )
                ).toResponseJson()
            )
            .build()
    )

    val networkResponse = apolloClient
        .query(GetProductNamesByIdsQuery(listOf("1")))
        .fetchPolicy(FetchPolicy.NetworkOnly)
        .execute()

    val cacheResponse = apolloClient
        .query(GetProductNameQuery("1"))
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    networkResponse.let { item ->
      // First emission: network success
      assertIs<ApolloResponse<GetProductNamesByIdsQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = false, actual = item.isFromCache)
    }
    cacheResponse.let { item ->
      // First emission: cache success
      assertIs<ApolloResponse<GetProductNameQuery.Data>>(item)
      assertEquals(expected = false, actual = item.cacheInfo?.isStale)
      assertNotNull(item.data)
      assertNull(item.exception)
      assertTrue(item.isLast)
      assertEquals(expected = true, actual = item.isFromCache)
    }
  }

  @Test
  fun nonStaleCache() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = DefaultCacheResolver,
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    apolloClient.apolloStore.writeOperation(
        operation = GetUserNameQuery(),
        data = GetUserNameQuery.Data(
            GetUserNameQuery.User(
                __typename = "User",
                name = "John Doe",
            ),
        ),
        cacheHeaders = receivedDate(currentTimeSeconds()),
    )

    val response: Flow<ApolloResponse<GetUserNameQuery.Data>> = apolloClient
        .query(GetUserNameQuery())
        .fetchPolicy(FetchPolicy.CacheFirst)
        .toFlow()

    response.test {
      awaitItem().let { item ->
        // First emission: cache error
        assertIs<ApolloResponse<GetUserNameQuery.Data>>(item)
        assertEquals(expected = false, actual = item.cacheInfo?.isStale)
        assertNotNull(item.data)
        assertNull(item.exception)
        assertTrue(item.isLast)
        assertEquals(expected = true, actual = item.isFromCache)
      }
      awaitComplete()
    }
  }

  @Test
  fun nonStaleCacheWithParam() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = DefaultCacheResolver,
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    apolloClient.apolloStore.writeOperation(
        operation = GetProductNameQuery("1"),
        data = GetProductNameQuery.Data(product = Product("1", "name")),
        cacheHeaders = receivedDate(currentTimeSeconds()),
    )

    val response: Flow<ApolloResponse<GetProductNameQuery.Data>> = apolloClient
        .query(GetProductNameQuery("1"))
        .fetchPolicy(FetchPolicy.CacheFirst)
        .toFlow()

    response.test {
      awaitItem().let { item ->
        // First emission: cache error
        assertIs<ApolloResponse<GetProductNameQuery.Data>>(item)
        assertEquals(expected = false, actual = item.cacheInfo?.isStale)
        assertNotNull(item.data)
        assertNull(item.exception)
        assertTrue(item.isLast)
        assertEquals(expected = true, actual = item.isFromCache)
      }
      awaitComplete()
    }
  }

  @Test
  fun nonStaleCacheWithoutParam() = runTest {
    val mockServer = MockServer()
    val apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .storeReceivedDate(true)
        .maxStale(14.days)
        .cacheMissesAsException(true)
        .normalizedCache(
            normalizedCacheFactory = MemoryCacheFactory(),
            cacheKeyGenerator = IdCacheKeyGenerator(),
            cacheResolver = CacheControlCacheResolver(
                maxAgeProvider = GlobalMaxAgeProvider(24.hours),
                delegateResolver = DefaultCacheResolver,
            ),
            enableOptimisticUpdates = true,
        )
        .build()

    apolloClient.apolloStore.writeOperation(
        operation = GetProductNameWithoutParamQuery(),
        data = GetProductNameWithoutParamQuery.Data(
            product = GetProductNameWithoutParamQuery.Product("1", "name")
        ),
        cacheHeaders = receivedDate(currentTimeSeconds()),
    )

    val response: Flow<ApolloResponse<GetProductNameWithoutParamQuery.Data>> = apolloClient
        .query(GetProductNameWithoutParamQuery())
        .fetchPolicy(FetchPolicy.CacheFirst)
        .toFlow()

    response.test {
      awaitItem().let { item ->
        // First emission: cache error
        assertIs<ApolloResponse<GetProductNameWithoutParamQuery.Data>>(item)
        assertEquals(expected = false, actual = item.cacheInfo?.isStale)
        assertNotNull(item.data)
        assertNull(item.exception)
        assertTrue(item.isLast)
        assertEquals(expected = true, actual = item.isFromCache)
      }
      awaitComplete()
    }
  }

  @Test
  fun unknownAgeConsideredStale() = runTest {
    val apolloClient = ApolloClient.Builder()
        .serverUrl("http://unused")
        .cache(MemoryCacheFactory())
        .build()

    apolloClient.apolloStore.writeOperation(
        operation = GetUserNameQuery(),
        data = GetUserNameQuery.Data(
            GetUserNameQuery.User(
                __typename = "User",
                name = "John Doe",
            ),
        ),
    )
    val response = apolloClient
        .query(GetUserNameQuery())
        .fetchPolicy(FetchPolicy.CacheOnly)
        .execute()

    // No stored received date, so age can't be computed -> considered stale
    assertIs<ApolloResponse<GetUserNameQuery.Data>>(response)
    assertEquals(true, response.cacheInfo?.isStale)
    assertNull(response.data)
    assertNotNull(response.exception)
    assertIs<CacheMissException>(response.exception)
    assertEquals(true, response.isFromCache)
  }
}

class ProductKeyArgumentsProvider : KeyArgumentsProvider {
  private val fieldPolicies: Map<String, FieldPolicies> = mapOf(
      Query.type.name to FieldPolicies(
          fieldPolicies = mapOf(
              "productsByIds" to fieldPolicy(Query.__productsByIds_ids),
              "product" to fieldPolicy(Query.__product_id)
          ),
      ),
  )

  override fun getKeyArguments(parentType: String, field: CompiledField): List<String> {
    return fieldPolicies[parentType]?.fieldPolicies[field.name]?.keyArgs.orEmpty()
  }

  private fun fieldPolicy(vararg keyArgs: CompiledArgumentDefinition) = FieldPolicies.FieldPolicy(
      keyArgs = keyArgs.map { it.name },
  )
}

class ProductCacheKeyGenerator : CacheKeyGenerator {

  override fun cacheKeyForObject(
      obj: Map<String, Any?>,
      context: CacheKeyGeneratorContext
  ): CacheKey? {

    val typename = "Product"

    return typename.let { obj["id"].asCacheKey(it) }
  }

  private fun Any?.asCacheKey(typename: String): CacheKey? {
    return (this as? String)?.let {
      CacheKey(typename, it)
    }
  }
}
