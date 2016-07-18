package com.ethercamp.harmony.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.util.StringCollectionUtil;
import com.ethercamp.harmony.domain.BlockchainInfoDTO;
import com.ethercamp.harmony.domain.InitialInfoDTO;
import com.ethercamp.harmony.domain.MachineInfoDTO;
import com.ethercamp.harmony.domain.PeerDTO;
import com.maxmind.geoip.Country;
import com.maxmind.geoip.LookupService;
import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;
import org.ethereum.core.Block;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.net.peerdiscovery.PeerInfo;
import org.ethereum.net.server.Channel;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Created by Stan Reshetnyk on 11.07.16.
 */
@Slf4j(topic = "harmony")
@Service
public class MachineInfoService {

    private final int BLOCK_COUNT_FOR_HASH_RATE = 100;

    @Autowired
    private Environment env;

    @Autowired
    private ClientMessageService clientMessageService;

    @Autowired
    private Ethereum ethereum;

    private Optional<LookupService> lookupService = Optional.empty();

    private final Map<String, Locale> localeMap = new HashMap<>();

    /**
     * Concurrent queue of last blocks.
     * Ethereum adds items when available.
     * Service reads items with interval.
     */
    private final Queue<Block> lastBlocksForHashRate = new ConcurrentLinkedQueue();

    private final AtomicReference<MachineInfoDTO> machineInfo = new AtomicReference<>(new MachineInfoDTO(0, 0l, 0l, 0l));

    private final AtomicReference<BlockchainInfoDTO> blockchainInfo =
            new AtomicReference<>(new BlockchainInfoDTO(0l, 0l, 0, 0l, 0l, 0l));

    private final AtomicReference<InitialInfoDTO> initialInfo = new AtomicReference<>(new InitialInfoDTO("", ""));


    public InitialInfoDTO getInitialInfo() {
        return initialInfo.get();
    }


    @PostConstruct
    private void postConstruct() {
        // gather blocks to calculate hash rate
        ethereum.addListener(new EthereumListenerAdapter() {
            @Override
            public void onBlock(Block block, List<TransactionReceipt> receipts) {
                lastBlocksForHashRate.add(block);
                if (lastBlocksForHashRate.size() > BLOCK_COUNT_FOR_HASH_RATE) {
                    lastBlocksForHashRate.poll();
                }
            }

            @Override
            public void onPeerAddedToSyncPool(Channel peer) {
                log.info("onPeerAddedToSyncPool peer: " + peer.getPeerId());
            }

            @Override
            public void onPeerDisconnect(String host, long port) {
                log.info("onPeerDisconnect host:" + host + ", port:" + port);
            }
        });

        initialInfo.set(new InitialInfoDTO(env.getProperty("ethereumJ.version"), env.getProperty("app.version")));

        createLogAppenderForMessaging();

        createGeoDatabase();
    }

    public MachineInfoDTO getMachineInfo() {
        return machineInfo.get();
    }

    @Scheduled(fixedRate = 5000)
    private void doUpdateMachineInfoStatus() {

        final OperatingSystemMXBean bean = (OperatingSystemMXBean) ManagementFactory
                .getOperatingSystemMXBean();

        machineInfo.set(new MachineInfoDTO(
                ((Double) (bean.getSystemCpuLoad() * 100)).intValue(),
                bean.getFreePhysicalMemorySize(),
                bean.getTotalPhysicalMemorySize(),
                getFreeDiskSpace()
        ));

        clientMessageService.sendToTopic("/topic/machineInfo", machineInfo.get());
    }

    @Scheduled(fixedRate = 2000)
    private void doUpdateBlockchainStatus() {

        final Block bestBlock = ethereum.getBlockchain().getBestBlock();

        blockchainInfo.set(
                new BlockchainInfoDTO(
                        bestBlock.getNumber(),
                        bestBlock.getTimestamp(),
                        bestBlock.getTransactionsList().size(),
                        bestBlock.getDifficultyBI().longValue(),
                        0l,
                        calculateHashRate()
                )
        );

        clientMessageService.sendToTopic("/topic/blockchainInfo", blockchainInfo.get());
    }

    /**
     * UNDER CONSTRUCTION
     */
    @Scheduled(fixedRate = 1500)
    private void doSendPeersInfo() {
        // convert active peers to DTO
        final List<PeerDTO> list = ethereum.getChannelManager().getActivePeers()
                .stream()
                .map(p -> new PeerDTO(
                        p.getPeerId(),
                        p.getNode().getHost(),
                        getCountryByIp(p.getNode().getHost()),
                        0l,
                        p.getPeerStats().getAvgLatency(),
                        p.getNodeStatistics().getReputation()))
                .collect(Collectors.toList());

        final Set<PeerInfo> peers = ethereum.getPeers();

        // retrieve peer's last check info values from all known peers
        // and set to our active peers
        synchronized (peers) {
            list.stream()
                    .forEach(p -> p.setLastPing(
                            peers.stream()                                  // find peer with same peerId
                                    .filter(pi -> pi.getPeerId().equals(p.getNodeId()))
                                    .findFirst()
                                    .map(pi -> pi.getLastCheckTime())       // using value from found peer
                                    .orElse(-1l)                            // -1 means we don't know last check
                                                                            // time for that peer
            ));
        }

        clientMessageService.sendToTopic("/topic/peers", list);

//        final Set<PeerInfo> peers = ethereum.getPeers();
//        synchronized (peers) {
//             list = peers.stream()
//                     //.filter(p -> p.isOnline())
//                     .map(p -> new PeerDTO(
//                            p.getPeerId(),
//                            p.getAddress().getHostAddress(),
//                            null,
//                            p.getLastCheckTime(),
//                            0,
//                            0))
//                     .collect(Collectors.toList());
//        }

//        // Translate IP to Country if service is available
//        lookupService.ifPresent(service ->
//            list.forEach(p -> {
//                Country country = service.getCountry(p.getIp());
//                p.setCountry(iso2CountryCodeToIso3CountryCode(country.getCode()));
//            })
//        );
    }

    private String getCountryByIp(String ip) {
        return lookupService
                .map(service -> iso2CountryCodeToIso3CountryCode(service.getCountry(ip).getCode()))
                .orElse("");
    }

    private long calculateHashRate() {
        final List<Block> blocks = Arrays.asList(lastBlocksForHashRate.toArray(new Block[0]));

        if (blocks.isEmpty()) {
            return 0;
        }

        Block bestBlock = blocks.get(blocks.size() - 1);
        long difficulty = bestBlock.getDifficultyBI().longValue();

        long sumTimestamps = blocks.stream().mapToLong(b -> b.getTimestamp()).sum();
        return difficulty / (sumTimestamps / blocks.size() / 1000);
    }

    /**
     * Get free space of disk where project located.
     * Verified on multi disk Windows.
     * Not tested against sym links
     */
    private long getFreeDiskSpace() {
        final File currentDir = new File(".");
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            log.debug(root.toAbsolutePath() + " vs current " + currentDir.getAbsolutePath());
            try {
                final FileStore store = Files.getFileStore(root);

                final boolean isCurrentDirBelongsToRoot = Paths.get(currentDir.getAbsolutePath()).startsWith(root.toAbsolutePath());
                if (isCurrentDirBelongsToRoot) {
                    final long usableSpace = store.getUsableSpace();
                    log.debug("Disk available:" + readableFileSize(usableSpace)
                            + ", total:" + readableFileSize(store.getTotalSpace()));
                    return usableSpace;
                }
            } catch (IOException e) {
                log.error("Problem querying space: " + e.toString());
            }
        }
        return 0;
    }

    // for better logs
    private String readableFileSize(long size) {
        if (size <= 0) return "0";
        final String[] units = new String[]{"B", "kB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    /**
     * Create log appender, which will subscribe to loggers, we are interested in.
     * Appender will send logs to messaging topic then (for delivering to client side).
     */
    private void createLogAppenderForMessaging() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayout patternLayout = new PatternLayout();
        patternLayout.setPattern("%d %-5level [%thread] %logger{35} - %msg%n");
        patternLayout.setContext(context);
        patternLayout.start();

        final UnsynchronizedAppenderBase messagingAppender = new UnsynchronizedAppenderBase() {
            @Override
            protected void append(Object eventObject) {
                LoggingEvent event = (LoggingEvent) eventObject;
                String message = patternLayout.doLayout(event);
                clientMessageService.sendToTopic("/topic/systemLog", message);
            }
        };

        // No effect of this
        final LevelFilter filter = new LevelFilter();
        filter.setLevel(Level.INFO);
        messagingAppender.addFilter(filter);
        messagingAppender.setName("ClientMessagingAppender");

        messagingAppender.start();

        // Attach appender to logger
        Arrays.asList("blockchain", "sync", "facade", "net", "general")
                .stream()
                .forEach(l -> {
                    Logger logger = context.getLogger(l);
                    logger.setLevel(Level.INFO);
                    logger.addAppender(messagingAppender);
                });

        // way to subscribe to all loggers existing at the moment
//        context.getLoggerList().stream()
//                .forEach(l -> l.addAppender(messagingAppender));
    }

    /**
     * Create MaxMind lookup service to find country by IP.
     * IPv6 is not used.
     */
    private void createGeoDatabase() {
        final String[] countries = Locale.getISOCountries();
        final String dbFilePath = env.getProperty("maxmind.file");

        for (String country : countries) {
            Locale locale = new Locale("", country);
            localeMap.put(locale.getISO3Country().toUpperCase(), locale);
        }
        try {
            lookupService = Optional.of(new LookupService(
                    dbFilePath,
                    LookupService.GEOIP_MEMORY_CACHE | LookupService.GEOIP_CHECK_CACHE));
        } catch (IOException e) {
            log.error("Please download file and put to " + dbFilePath);
            log.error("Wasn't able to create maxmind location service. Country information will not be available.", e);
        }
    }

    private String iso2CountryCodeToIso3CountryCode(String iso2CountryCode){
        Locale locale = new Locale("", iso2CountryCode);
        try {
            return locale.getISO3Country();
        } catch (MissingResourceException e) {
            // silent
        }
        return "";
    }
}