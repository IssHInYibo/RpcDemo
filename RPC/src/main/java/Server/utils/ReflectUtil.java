package Server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ReflectUtil {

    private static final Logger logger = LoggerFactory.getLogger(ReflectUtil.class);

    public static String getStackTrace() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        return stack[stack.length - 1].getClassName();
    }

    /**
     * 传入一个包名，用于扫描该包及其子包下所有的类，并将其 Class 对象放入一个 Set 中返回
     */
    public static Set<Class<?>> getClasses(String packageName) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        boolean recursive = true;
        String packageDirName = packageName.replace('.', '/' );
        Enumeration<URL> dirs;
        try {
            // 获取当前线程中，当前类的绝对路径URL
            dirs = Thread.currentThread().getContextClassLoader().getResources(packageDirName);
            // 循环迭代
            while (dirs.hasMoreElements()) {
                // 获取下一个元素
                URL url = dirs.nextElement();
                // 获取协议的名字
                String protocol = url.getProtocol();
                // 如果是以文件的形式保存在服务器
                if ("file".equals(protocol)) {
                    // 获取包的物理路径
                    String filePath = URLDecoder.decode(url.getFile(), "UTF-8");
                    findAndAddClassesInPackageByFile(packageName, filePath, recursive, classes);
                } else if ("jar".equals(protocol)) {
                    // 如果是以jar包的形式存在服务器，就定义一个JarFile
                    JarFile jar;
                    try {
                        jar = ((JarURLConnection) url.openConnection())
                                .getJarFile();
                        // 由jar包得到一个枚举类
                        Enumeration<JarEntry> entries = jar.entries();
                        // 以同样的方式循环迭代
                        while (entries.hasMoreElements()) {
                            // 获取jar里的一个实体，可以是目录，和一些jar包里的其他文件，如META-INF文件
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            // 如果名字是以/开头
                            if (name.charAt(0) == '/') {
                                // 则获取后面的字符串
                                name = name.substring(1);
                            }
                            // 如果前半部分和定义的包名相同
                            if (name.startsWith(packageDirName)) {
                                int idx = name.lastIndexOf('/');
                                // 如果以 / 结尾 是一个包
                                if (idx != -1) {
                                    // 获取包名并把/替换成.
                                    packageName = name.substring(0, idx)
                                            .replace('/','.');
                                }
                                // 如果可以迭代下去，并且是一个包
                                if ((idx != -1) || recursive) {
                                    // 如果是一个.class文件 而且不是目录
                                    if (name.endsWith(".class")
                                        && !entry.isDirectory()) {
                                        // 去掉后面的.class，并获取真正的类名
                                        String className = name.substring(
                                                packageName.length() + 1, name.length() - 6);
                                        try {
                                            // 添加到classes
                                            classes.add(Class.forName(packageName + '.' + className));
                                        } catch (ClassNotFoundException e) {
                                            logger.error("添加用户自定义视图类错误, 找不到此类的.class文件", e);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        logger.error("在扫描用户定义视图时从jar包获取文件出错", e);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("扫描类时出现了错误 ", e);
        }
        return classes;
    }

    private static void findAndAddClassesInPackageByFile(String packageName, String packagePath,
                                                         final boolean recursive, Set<Class<?>> classes) {
        // 获取此包的目录，并建立一个FIle
        File di = new File(packagePath);
        // 如果不存在或不是目录，就直接返回
        if (!di.exists() || !di.isDirectory()) {
            logger.warn("用户定义包名 " + packageName + " 下没有任何文件");
            return ;
        }
        // 如果存在，就获取包下的所有文件，包括目录
        File[] dirfiles = di.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return (recursive && file.isDirectory() || (file.getName().endsWith(".class")));
            }
        });
        // 循环所有文件
        for (File file : dirfiles) {
            // 如果是目录，则继续扫描
            if (file.isDirectory()) {
                findAndAddClassesInPackageByFile(packageName + "."
                        + file.getName(), file.getAbsolutePath(), recursive,
                        classes);
            } else {
                // 如果不是目录，而是java 类文件，则去掉后面的.class 只留下类名
                String className = file.getName().substring(0, file.getName().length() - 6);
                try {
                    classes.add(Thread.currentThread().getContextClassLoader().
                            loadClass(packageName + '.' + className));
                } catch (ClassNotFoundException e) {
                    logger.error("添加用户自定义视图类错误 找不到此类的.class文件", e);
                }
            }
        }
    }
}
