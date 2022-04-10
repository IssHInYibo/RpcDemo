package Client.client;

import Client.handler.NettyClientHandler;
import Server.config.CommonDecoder;
import Server.config.CommonEncoder;
import Server.config.RpcError;
import Server.config.RpcException;
import Server.serializer.CommonSerializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 改造Netty Client，实现客户端连接失败重试的机制
 */
public class ChannelProvider {

    private static final Logger logger = LoggerFactory.getLogger(ChannelProvider.class);
    private static EventLoopGroup eventLoopGroup;
    private static Bootstrap bootstrap = initializeBootstrap();

    // 连接重试次数
    private static final int MAX_RETRY_COUNT = 5;
    private static Channel channel = null;

    private static Bootstrap initializeBootstrap() {
        eventLoopGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true);
        return bootstrap;
    }

    /**
     * 返回连接以后的channel对象
     */
    public static Channel get(InetSocketAddress inetSocketAddress, CommonSerializer serializer) {
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new CommonEncoder(serializer))
                        .addLast(new CommonDecoder())
                        .addLast(new NettyClientHandler());
            }
        });
        // 设置计数器为1
        // 因为增加了连接重试的机制，并不会返回ChannelFuture，因此不能简单的使用sync()
        CountDownLatch countDownLatch = new CountDownLatch(1);
        try {
            connect(bootstrap, inetSocketAddress, countDownLatch);
            // 等待连接服务器完毕
            countDownLatch.await();
        } catch (InterruptedException e) {
            logger.error("获取channel时发生错误", e);
        }
        return channel;
    }

    private static void connect(Bootstrap bootstrap, InetSocketAddress inetSocketAddress, CountDownLatch countDownLatch) {
        connect (bootstrap, inetSocketAddress, MAX_RETRY_COUNT, countDownLatch);
    }

    /**
     * 连接重试机制
     * 如果第一次connect失败，则递归执行connect直到重试次数为0
     * 注意delay设计为递增的时间，如果第二次客户端没有连接成功，则认为服务器还需要多一点时间来准备
     */
    private static void connect(Bootstrap bootstrap, InetSocketAddress inetSocketAddress, int retry,
                                CountDownLatch countDownLatch) {
        bootstrap.connect(inetSocketAddress).addListener( (ChannelFutureListener) future -> {
            // 如果连接成功，则结束递归
            if (future.isSuccess()) {
                logger.info("客户端连接成功");
                channel = future.channel();
                countDownLatch.countDown();
                return;
            }
            // 如果重试次数为0，则结束递归
            if (retry == 0) {
                logger.error("客户端连接失败，重试次数已用完, 放弃连接！");
                countDownLatch.countDown();
                throw new RpcException(RpcError.CLIENT_CONNECT_SERVER_FAILURE);
            }
            // 查看目前是第几次连接
            int order = MAX_RETRY_COUNT - retry + 1;    // 1 -> 2 -> 3 -> 4 -> 5
            // 两次重试时间的间隔，左移相当于delay * (2^order) 比如 2 -> 5 -> 12 -> 30
            int delay = 1 << order;
            logger.error("{}:连接失败，第{}次重连...", new Date(), order);
            // 从config中取出EventLoopGroup，其中的schedule方法可以穿一个定时任务
            // 递归
            bootstrap.config().group().schedule(() -> {
                connect(bootstrap,inetSocketAddress,retry - 1, countDownLatch );
            }, delay, TimeUnit.SECONDS);
        });
    }
}