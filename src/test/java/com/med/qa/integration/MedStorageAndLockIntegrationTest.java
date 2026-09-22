package com.med.qa.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.med.qa.config.MedSessionProperties;
import com.med.qa.config.RedissonConfig;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.typehandler.MetadataTypeHandler;
import com.med.qa.mapper.typehandler.RoleTypeTypeHandler;
import com.med.qa.mapper.typehandler.SessionStatusTypeHandler;
import com.med.qa.memory.cache.MedCacheProperties;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.MedLockProperties;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.memory.repository.MedChatMemoryRepository;
import com.med.qa.memory.serde.ProtoMessageCodec;
import com.med.qa.memory.sharding.Crc32ShardingAlgorithm;
import com.med.qa.service.MedChatSessionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.shardingsphere.driver.ShardingSphereDriver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test of the consultation storage and lock chain, run against genuine
 * middleware spun up by Testcontainers: a real MySQL 8.0 instance (routed through ShardingSphere-JDBC
 * exactly like production) and a real Redis Stack instance (backing both the Redisson distributed
 * lock and the conversation memory cache).
 *
 * <p>The class is gated by {@link DockerAvailableCondition}: when no Docker daemon is reachable the
 * whole class is skipped, so the offline {@code mvn test} run stays green. On a host with Docker the
 * test proves that the very same components exercised by the unit suite (MyBatis mappers, the
 * two-tier repository, the Redisson lock) behave identically against real MySQL and Redis.</p>
 *
 * <p>Nothing here is hand-rolled: ShardingSphere performs the {@code crc32(session_id) % 16} routing,
 * Flyway creates the 16 physical shards, Redisson supplies the mutual exclusion. Only the wiring that
 * a Spring context would normally provide is assembled by hand so the test needs no application
 * context.</p>
 */
@ExtendWith(DockerAvailableCondition.class)
class MedStorageAndLockIntegrationTest {

    private static final int SHARD_COUNT = 16;

    /**
     * ShardingSphere's URL-argument placeholder syntax, mirrored from
     * {@code URLArgumentLine#PLACEHOLDER_PATTERN}: two dollar signs, the variable name, the {@code ::}
     * separator and the default value.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\$\\{([A-Za-z_][A-Za-z0-9_]*)::([^}]*)}");

    private static final String TENANT = "tenant-it";
    private static final String DEPT = "dept-cardiology";

    private static MySQLContainer<?> mysql;
    private static GenericContainer<?> redis;

    private static DataSource shardingDs;
    private static Connection rawMysql;
    private static SqlSessionTemplate sqlSession;

    private static RedissonClient redissonClient;
    private static RedisMessageCache cache;
    private static MedChatMemoryRepository repository;
    private static SessionLockService lockService;
    private static MedChatSessionService sessionService;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        // D35: when the run declares Docker mandatory (med.test.integration.required / CI), a missing
        // daemon must fail here with a message that names the switch, instead of the suite reporting
        // itself skipped. DockerAvailableCondition deliberately leaves the class enabled in that case.
        IntegrationTestRequirements.verifyDockerAvailableForCurrentRun(DockerAvailabilityProbe.testcontainers());

        mysql = new MySQLContainer<>(DockerImageName.parse(MedIntegrationImages.MYSQL))
                .withDatabaseName("med_qa")
                .withUsername("med_qa")
                .withPassword("med_qa");
        mysql.start();

        redis = new GenericContainer<>(DockerImageName.parse(MedIntegrationImages.REDIS_STACK))
                .withExposedPorts(6379);
        redis.start();

        shardingDs = buildShardingDataSource();

        // Ensure the MySQL JDBC driver is registered (it is a runtime-scoped dependency, so it is on
        // the test classpath but must be loaded before DriverManager accepts the connection URL).
        Class.forName("com.mysql.cj.jdbc.Driver");
        String rawUrl = rawMysqlUrl();
        rawMysql = DriverManager.getConnection(rawUrl, mysql.getUsername(), mysql.getPassword());

        // Flyway creates the 16 med_message shards + med_session + med_audit_log on the real MySQL
        // instance, validating the production DDL against a genuine database.
        //
        // The migrations must target the raw MySQL server, never the ShardingSphere DataSource. They
        // create the *physical* shard tables (med_message_0..15), which are precisely the tables the
        // sharding proxy treats as opaque, so driving them through it breaks twice over: Flyway's
        // schema-existence probe reads information_schema through the proxy, concludes med_qa is
        // missing, and the follow-up `CREATE DATABASE med_qa` dies with MySQL error 1007
        // "database exists". The H2-based mapper tests already migrate through the raw DataSource for
        // the same reason, and MedFlywayConfig applies the identical rule in production.
        DataSource migrationDs = new SimpleDriverDataSource(
                new com.mysql.cj.jdbc.Driver(), rawUrl, mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(migrationDs).locations("classpath:db/migration").load().migrate();

        Environment environment = new Environment("it", new JdbcTransactionFactory(), shardingDs);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(RoleTypeTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(MetadataTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(SessionStatusTypeHandler.class);
        parseMapper(configuration, "/mapper/ChatMessageMapper.xml");
        parseMapper(configuration, "/mapper/ChatSessionMapper.xml");
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = new SqlSessionTemplate(sqlSessionFactory);

        ChatMessageMapper messageMapper = sqlSession.getMapper(ChatMessageMapper.class);
        ChatSessionMapper sessionMapper = sqlSession.getMapper(ChatSessionMapper.class);

        redissonClient = buildRedissonClient();

        RedisTemplate<String, byte[]> redisTemplate = buildRedisTemplate();
        cache = new RedisMessageCache(redisTemplate, new ProtoMessageCodec(), new MedCacheProperties());
        repository = new MedChatMemoryRepository(messageMapper, cache);
        lockService = new SessionLockService(redissonClient, new MedLockProperties());
        sessionService = new MedChatSessionService(
                sessionMapper, lockService, cache, new MedSessionProperties(), Clock.systemUTC());
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (rawMysql != null) {
            rawMysql.close();
        }
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (mysql != null) {
            mysql.stop();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Storage chain against real MySQL
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("append then findAll round-trips through sharded MySQL and the Redis cache window")
    void appendAndFindAllThroughRealMysqlAndRedis() throws Exception {
        String sessionId = "sess-it-" + UUID.randomUUID();
        ChatMessageDO message = sampleMessage(sessionId, RoleType.PATIENT);

        repository.append(message);

        // First read is served from the Redis cache window (mirrored on append).
        List<ChatMessageDO> fromCache = repository.findAll(TENANT, DEPT, sessionId);
        assertEquals(1, fromCache.size());
        assertEquals(message.getContent(), fromCache.get(0).getContent());

        // Evict the cache, then a read must back-fill from the authoritative MySQL shard.
        cache.evict(TENANT, DEPT, sessionId);
        List<ChatMessageDO> fromMysql = repository.findAll(TENANT, DEPT, sessionId);
        assertEquals(1, fromMysql.size());
        assertEquals(message.getContent(), fromMysql.get(0).getContent());
        assertEquals(RoleType.PATIENT, fromMysql.get(0).getRole());

        // Deleting the session removes the rows and drops the (rebuilt) cache window.
        int deleted = repository.deleteSession(TENANT, DEPT, sessionId);
        assertEquals(1, deleted);
        assertTrue(repository.findAll(TENANT, DEPT, sessionId).isEmpty());
        assertSameRowCountInShards(sessionId, message.getMessageId(), 0);
    }

    @Test
    @DisplayName("a message is routed to exactly the crc32-selected physical shard and no other")
    void messageLandsInSingleCrc32Shard() throws Exception {
        String sessionId = "sess-route-it-" + UUID.randomUUID();
        ChatMessageDO message = sampleMessage(sessionId, RoleType.ASSISTANT);
        repository.append(message);
        assertSameRowCountInShards(sessionId, message.getMessageId(), 1);
    }

    @Test
    @DisplayName("two sessions with different ids map to (possibly) different shards, each self-contained")
    void twoSessionsRouteToTheirOwnShards() throws Exception {
        String first = "sess-a-it-" + UUID.randomUUID();
        String second = "sess-b-it-" + UUID.randomUUID();
        ChatMessageDO a = sampleMessage(first, RoleType.PATIENT);
        ChatMessageDO b = sampleMessage(second, RoleType.DOCTOR);
        repository.append(a);
        repository.append(b);
        assertSameRowCountInShards(first, a.getMessageId(), 1);
        assertSameRowCountInShards(second, b.getMessageId(), 1);
    }

    // ---------------------------------------------------------------------------------------------
    // Session lifecycle against real MySQL
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("session lifecycle (create -> close -> archive) persists through the real MySQL stack")
    void sessionLifecyclePersistsToRealMysql() {
        String patientId = "patient-it-" + UUID.randomUUID();
        ChatSessionDO created = sessionService.createSession(TENANT, DEPT, patientId, "胸闷气短初诊");
        assertNotNull(created.getSessionId());
        assertEquals(SessionStatus.ACTIVE, created.getStatus());

        ChatSessionDO loaded = sessionService.getSession(TENANT, DEPT, created.getSessionId());
        assertEquals(created.getSessionId(), loaded.getSessionId());

        ChatSessionDO closed = sessionService.closeSession(TENANT, DEPT, created.getSessionId());
        assertEquals(SessionStatus.CLOSED, closed.getStatus());

        ChatSessionDO archived = sessionService.archiveSession(TENANT, DEPT, created.getSessionId());
        assertEquals(SessionStatus.ARCHIVED, archived.getStatus());

        // A closed-then-archived session still resolves by id from MySQL.
        assertTrue(sessionService.findSession(TENANT, DEPT, created.getSessionId()).isPresent());
    }

    // ---------------------------------------------------------------------------------------------
    // Distributed lock against real Redis
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("executeLocked serializes concurrent writers through the real Redis lock")
    void distributedLockSerializesConcurrentWriters() throws InterruptedException {
        String sessionId = "sess-lock-it-" + UUID.randomUUID();
        int threads = 8;
        AtomicInteger inCritical = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                lockService.executeLocked(TENANT, DEPT, sessionId, () -> {
                    int cur = inCritical.incrementAndGet();
                    maxConcurrent.accumulateAndGet(cur, Math::max);
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    inCritical.decrementAndGet();
                    return null;
                });
                completed.incrementAndGet();
                done.countDown();
            }).start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all lock acquisitions must finish in time");
        assertEquals(threads, completed.get(), "every writer must have completed its guarded action");
        // Mutual exclusion held by the real Redisson lock: at most one writer inside at once.
        assertEquals(1, maxConcurrent.get());
    }

    @Test
    @DisplayName("lock state reflects ownership and is released after the guarded action")
    void lockStateReflectsOwnership() {
        String sessionId = "sess-lockstate-it-" + UUID.randomUUID();
        assertFalse(lockService.isLocked(TENANT, DEPT, sessionId), "fresh key must be unlocked");

        String result = lockService.executeLocked(TENANT, DEPT, sessionId, () -> {
            assertTrue(lockService.isHeldByCurrentThread(TENANT, DEPT, sessionId),
                    "owner must hold the lock inside the guarded block");
            assertTrue(lockService.isLocked(TENANT, DEPT, sessionId),
                    "key must report locked while held");
            return "ok";
        });
        assertEquals("ok", result);
        assertFalse(lockService.isLocked(TENANT, DEPT, sessionId), "lock must be released afterwards");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static DataSource buildShardingDataSource() throws IOException {
        String resolved = resolveShardingPlaceholders(readResource("/sharding/med-sharding-it-template.yaml"));
        Path tmpYaml = Files.createTempFile("med-sharding-it", ".yaml");
        Files.writeString(tmpYaml, resolved);
        // The scheme after `jdbc:shardingsphere:` is not free-form: ShardingSphere splits the URL at
        // its first ':' and looks the resulting type up in the ShardingSphereURLLoader SPI. The
        // absolute-path loader registers itself as `absolutepath:` (see AbsolutePathURLLoader), NOT
        // as `file:`; `file:` matches no SPI implementation and the driver aborts with
        // "SPI-00001: No implementation class load from SPI ... ShardingSphereURLLoader with type
        // 'file:'". Production uses the sibling `classpath:` scheme. ShardingSphereUrlSchemeTest
        // pins both against the SPI so the wrong scheme cannot come back.
        String url = "jdbc:shardingsphere:absolutepath:" + tmpYaml.toAbsolutePath();
        return new SimpleDriverDataSource(new ShardingSphereDriver(), url);
    }

    /**
     * Substitutes the {@code $${NAME::default}} placeholders of the integration-test sharding
     * template with the coordinates of the Testcontainers MySQL instance.
     *
     * <p>ShardingSphere's placeholder syntax carries <em>two</em> dollar signs (the pattern is
     * {@code \$\$\{(.*?)::(.*?)\}} in {@code URLArgumentLine}), and the default lives after the
     * {@code ::}. Substituting the single-dollar prefix {@code ${NAME::default}} therefore leaves a
     * stray {@code $} glued to the value -- which is how the port ended up as the unparseable string
     * {@code "$53140"}. This method matches the full {@code $${...}} token instead, and fails loudly
     * if the template ever declares a placeholder the test does not know how to fill, so template
     * drift can never silently leak a literal into the JDBC URL.</p>
     */
    private static String resolveShardingPlaceholders(String template) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("MED_MYSQL_HOST", mysql.getHost());
        values.put("MED_MYSQL_PORT", String.valueOf(mysql.getMappedPort(MySQLContainer.MYSQL_PORT)));
        values.put("MED_MYSQL_DATABASE", mysql.getDatabaseName());
        values.put("MED_MYSQL_USERNAME", mysql.getUsername());
        values.put("MED_MYSQL_PASSWORD", mysql.getPassword());

        Matcher matcher = PLACEHOLDER.matcher(template);
        Set<String> declared = new LinkedHashSet<>();
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            declared.add(name);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalStateException(
                        "sharding template declares $${" + name + "::...} but the test cannot supply it");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);

        if (!declared.equals(values.keySet())) {
            throw new IllegalStateException("sharding template declares " + declared
                    + " but the test substitutes " + values.keySet());
        }
        return resolved.toString();
    }

    private static RedissonClient buildRedissonClient() {
        RedisProperties redisProperties = new RedisProperties();
        redisProperties.setHost(redis.getHost());
        redisProperties.setPort(redis.getMappedPort(6379));
        redisProperties.setDatabase(0);
        return Redisson.create(RedissonConfig.buildConfig(redisProperties, new MedLockProperties()));
    }

    private static RedisTemplate<String, byte[]> buildRedisTemplate() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashValueSerializer(RedisSerializer.byteArray());
        template.setEnableDefaultSerializer(false);
        template.afterPropertiesSet();
        return template;
    }

    private static String rawMysqlUrl() {
        // characterEncoding must be a JAVA charset name; `utf8mb4` is a MySQL server charset and
        // Connector/J throws UnsupportedEncodingException before opening the connection.
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(MySQLContainer.MYSQL_PORT)
                + "/" + mysql.getDatabaseName()
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
                + "&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private static void parseMapper(Configuration configuration, String resource) {
        try (InputStream xml = MedStorageAndLockIntegrationTest.class.getResourceAsStream(resource)) {
            if (xml == null) {
                throw new IllegalStateException("missing mapper resource " + resource);
            }
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse mapper " + resource, e);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = MedStorageAndLockIntegrationTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ChatMessageDO sampleMessage(String sessionId, RoleType role) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "web");
        metadata.put("channel", "app");
        return ChatMessageDO.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId("patient-it")
                .role(role)
                .content("患者主诉胸闷气短，持续两小时。")
                .tokenCount(24)
                .masked(false)
                .createdAt(1_735_000_000_000L + System.nanoTime() % 1000)
                .metadata(metadata)
                .build();
    }

    /**
     * Confirms a message row is present exactly once in the crc32-selected physical shard and absent
     * from every other, by querying the real MySQL instance directly (bypassing ShardingSphere).
     */
    private static void assertSameRowCountInShards(String sessionId, String messageId, int expected)
            throws Exception {
        int shard = Crc32ShardingAlgorithm.shardIndex(sessionId, SHARD_COUNT);
        for (int i = 0; i < SHARD_COUNT; i++) {
            long count = countInPhysical(i, messageId);
            if (i == shard) {
                assertEquals(expected, count,
                        "row must exist " + expected + " time(s) in med_message_" + i);
            } else {
                assertEquals(0, count, "row must NOT exist in med_message_" + i);
            }
        }
    }

    private static long countInPhysical(int shard, String messageId) throws Exception {
        try (PreparedStatement ps = rawMysql.prepareStatement(
                "SELECT COUNT(*) FROM med_message_" + shard + " WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }
}
