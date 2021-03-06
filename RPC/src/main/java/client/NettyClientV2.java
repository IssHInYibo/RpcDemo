package client;

import client.client.ChannelProvider;
import client.client.UnprocessedRequests;
import common.reqeust.RpcRequest;
import common.util.RpcMessageChecker;
import server.service.Service;
import common.config.RpcError;
import common.config.RpcException;
import server.registry.Impl.ZkServiceRegistry;
import server.registry.ServiceRegistry;
import common.response.RpcResponse;
import common.serializer.CommonSerializer;
import io.netty.channel.*;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NettyClientV2 implements RpcClient{


    private static final Logger logger =  LoggerFactory.getLogger(NettyClient.class);

    // 使用注册中心后就不需要这个了
    private String host;
    private int port;
    // private static final Bootstrap bootstrap;

    private CommonSerializer serializer;

    private RpcClient rpcClient;

    // zooKeeper注册中心
    private ServiceRegistry serviceRegistry;

    private UnprocessedRequests unprocessedRequests;

    // 使用静态代码块初始化Netty客户端
    public NettyClientV2(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public NettyClientV2() {
        // this.serviceRegistry = new ZkServiceRegistry(new RoundRobinLoadBalancer(), this);
        this.serviceRegistry = new ZkServiceRegistry(this);
        this.unprocessedRequests = new UnprocessedRequests();
    };

    @Override
    public Object sendRequest(RpcRequest rpcRequest, CommonSerializer serializer) throws RpcException {
        if (serializer == null) {
            logger.error("未设置序列化器");
            throw new RpcException(RpcError.SERIALIZER_NOT_FOUND);
        }

        try {
            Channel channel = ChannelProvider.get(new InetSocketAddress(host, port), serializer);
            // 判断channel是否与服务器连接上，isActive = true
            // 如果连接上，就发送远程调用，并且设置监听回调函数
            if (channel.isActive()) {
                channel.writeAndFlush(rpcRequest).addListener(future1 -> {
                   if (future1.isSuccess()) {
                       logger.info(String.format("客户端发送消息: %s", rpcRequest.toString()));
                   } else {
                       logger.error("发送消息时发生错误:", future1.cause()); // cause为异常原因
                   }
                });
                channel.closeFuture().sync();
                AttributeKey<RpcResponse> key = AttributeKey.valueOf("rpcResponse" + rpcRequest.getRequestId());
                RpcResponse rpcResponse = channel.attr(key).get();
                // 检查消息是否正确，比如说请求号不匹配 / 消息状态码不是success等...
                RpcMessageChecker.check(rpcRequest,rpcResponse);
                return rpcResponse.getData();
            } else {
                // 0表示”正常“退出程序，即如果当前程序还有在执行的任务，则等待所有任务执行完成以后再退出
                System.exit(0);
            }
        } catch (InterruptedException e) {
            logger.error("发送请求时产生错误: ", e);
        }
        return null;
    }

    /**
     * 设置序列化器
     */
    public void setSerializer(CommonSerializer serializer) {
        this.serializer = serializer;
    }

    /**
     * 使用zookeeper拉取服务，因此服务的host和port应该是从zookeeper获取
     */
    @Override
    public Object sendRequest1(RpcRequest rpcRequest, CommonSerializer serializer) throws Exception {
        try {
            // 从注册中心拉取到服务地址
            // TODO 本地缓存 + 获取到服务【列表】实现负载均衡 - getServiceList()
            // TODO 为了实现节点新增或删除时自动删除缓存，需要在启动时再开一个线程来监听节点的变化
            // 先查看本地缓存的服务列表，如果有就从本地拉取，否则再查询zookeeper
            // getServiceList(rpcRequest.getInter faceName());

            // 负载均衡
            InetSocketAddress inetSocketAddress = serviceRegistry.lookupService(rpcRequest.getInterfaceName());
            // List<InetSocketAddress> inetSocketAddresses = serviceRegistry.lookupService(rpcRequest.getInterfaceName());
            Channel channel = ChannelProvider.get(inetSocketAddress, serializer);
            // 或者Channel != null
            if (channel.isActive()) {
                logger.info("客户端已连接到了服务器 {}:{}", inetSocketAddress.getHostName(), inetSocketAddress.getPort());
                channel.writeAndFlush(rpcRequest).addListener(future1 -> {
                   if (future1.isSuccess()) {
                       logger.info(String.format("客户端发送消息: %s", rpcRequest.toString()));
                   } else {
                       logger.error("发送消息时发生错误:", future1.cause()); // cause为异常原因
                   }
                });
                channel.closeFuture().sync();
                AttributeKey<RpcResponse> key = AttributeKey.valueOf("rpcResponse");
                RpcResponse rpcResponse = channel.attr(key).get();
                return rpcResponse.getData();
            } else {
                System.exit(0);
            }
        } catch (InterruptedException e) {
            logger.error("发送请求时产生了错误: ", e);
        }
        return null;
    }

    /**
     * 心跳改造后的sendRequest
     */
    @Override
    public CompletableFuture<RpcResponse> sendRequest2(RpcRequest rpcRequest, CommonSerializer serializer) {
        CompletableFuture<RpcResponse> resultFuture = new CompletableFuture<>();
        try {
            // 从注册中心拉取到服务地址
            // TODO 本地缓存 + 获取到服务【列表】实现负载均衡 - getServiceList()
            // TODO 为了实现节点新增或删除时自动删除缓存，需要在启动时再开一个线程来监听节点的变化
            // 先查看本地缓存的服务列表，如果有就从本地拉取，否则再查询zookeeper
            // getServiceList(rpcRequest.getInter faceName());

            // 将新请求放入未处理完的请求中
            unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
            logger.info("本次请求Id: {}", rpcRequest.getRequestId());

            // 负载均衡
            InetSocketAddress inetSocketAddress = serviceRegistry.lookupService(rpcRequest.getInterfaceName());
            // List<InetSocketAddress> inetSocketAddresses = serviceRegistry.lookupService(rpcRequest.getInterfaceName());
            Channel channel = ChannelProvider.get(inetSocketAddress, serializer);
            // 或者Channel != null
            if (channel.isActive()) {
                logger.info("客户端已连接到了服务器 {}:{}", inetSocketAddress.getHostName(), inetSocketAddress.getPort());
                channel.writeAndFlush(rpcRequest).addListener((ChannelFutureListener)future1 -> {
                    if (future1.isSuccess()) {
                        logger.info(String.format("客户端发送消息: %s", rpcRequest.toString()));
                    } else {
                        future1.channel().close();
                        resultFuture.completeExceptionally(future1.cause());
                        logger.error("发送消息时发生错误:", future1.cause()); // cause为异常原因
                    }
                });
            } else {
                System.exit(0);
            }
        } catch (Exception e) {
            // 将请求从请求集合中移除
            unprocessedRequests.remove(rpcRequest.getRequestId());
            logger.error("发送请求时产生了错误: ", e);
            //interrupt()这里作用是给受阻塞的当前线程发出一个中断信号，让当前线程退出阻塞状态，好继续执行然后结束
            Thread.currentThread().interrupt();
        }
        return resultFuture;
    }

    @Override
    public Object sendRequest(RpcRequest rpcRequest, String host, int port) {
        return null;
    }

    @Override
    public Object sendRequest(RpcRequest rpcRequest) {
        return null;
    }
}
