package org.hotswap.agent.plugin.owb.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hotswap.agent.annotation.FileEvent;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.MergeableCommand;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.owb.BeanReloadStrategy;
import org.hotswap.agent.watch.WatchFileEvent;

/**
 * BeanClassRefreshCommand. Collect all classes definitions/redefinitions for application
 *
 * 1. Merge all commands (definition, redefinition) for appClassLoader to single command.
 * 2. Call proxy redefinitions in BeanArchiveAgent for all merged commands
 * 3. Call bean class reload in BeanArchiveAgent for all merged commands
 *
 * @author Vladimir Dvorak
 */
public class BeanClassRefreshCommand extends MergeableCommand {

    private static AgentLogger LOGGER = AgentLogger.getLogger(BeanClassRefreshCommand.class);

    ClassLoader appClassLoader;

    String className;

    String oldSignatureForProxyCheck;

    String oldSignatureByStrategy;

    String strBeanReloadStrategy;

    Class<?> beanClass;

    WatchFileEvent event;

    URL beanArchiveUrl;

    /**
     * Instantiates a new bean class refresh command.
     *
     * @param appClassLoader the application class loader
     * @param className the class name
     * @param classSignForProxyCheck the class signature for proxy check
     * @param classSignByStrategy the class signature by strategy
     * @param beanArchiveUrl the bean archive url
     * @param beanReloadStrategy the bean reload strategy
     */
    public BeanClassRefreshCommand(ClassLoader appClassLoader, String className, String oldSignatureForProxyCheck,
            String oldSignatureByStrategy, URL beanArchiveUrl, BeanReloadStrategy beanReloadStrategy) {
        this.appClassLoader = appClassLoader;
        this.className = className;
        this.oldSignatureForProxyCheck = oldSignatureForProxyCheck;
        this.oldSignatureByStrategy = oldSignatureByStrategy;
        this.beanArchiveUrl = beanArchiveUrl;
        this.strBeanReloadStrategy = beanReloadStrategy != null ? beanReloadStrategy.toString() : null;
    }

    /**
     * Instantiates a new bean class refresh command.
     *
     * @param appClassLoader the application class loader
     * @param archivePath the archive path
     * @param beanArchiveUrl the bean archive url
     * @param event the class event
     */
    public BeanClassRefreshCommand(ClassLoader appClassLoader, String archivePath,  URL beanArchiveUrl, WatchFileEvent event) {

        this.appClassLoader = appClassLoader;
        this.event = event;
        this.beanArchiveUrl = beanArchiveUrl;

        String classFullPath = event.getURI().getPath();
        int index = classFullPath.indexOf(archivePath);
        if (index == 0) {
            // Strip archive path from beginning and .class from the end to get class name from full path to class file
            String classPath = classFullPath.substring(archivePath.length());
            classPath = classPath.substring(0, classPath.indexOf(".class"));
            if (classPath.startsWith("/")) {
                classPath = classPath.substring(1);
            }
            this.className = classPath.replace("/", ".");
        } else {
            LOGGER.error("Archive path '{}' doesn't match with classFullPath '{}'", archivePath, classFullPath);
        }
    }

    @Override
    public void executeCommand() {
        List<Command> mergedCommands = popMergedCommands();
        mergedCommands.add(0, this);

        do {
            for (Command cmd: mergedCommands) {
                BeanClassRefreshCommand bcrCmd = (BeanClassRefreshCommand) cmd;
                try {
                    bcrCmd.beanClass = appClassLoader.loadClass(bcrCmd.className);
                } catch (ClassNotFoundException e) {
                    LOGGER.error("Class '{}' not found in appClassLoader {}", bcrCmd.className, appClassLoader);
                }
            }

            for (Command cmd: mergedCommands) {
                ((BeanClassRefreshCommand)cmd).recreateProxy(mergedCommands);
            }

            Map<String, String> oldSignatures = new HashMap<String, String>();

            for (Command cmd: mergedCommands) {
                BeanClassRefreshCommand bcrCmd = (BeanClassRefreshCommand) cmd;
                oldSignatures.put(bcrCmd.className, bcrCmd.oldSignatureByStrategy);
            }

            for (Command cmd1: mergedCommands) {
                BeanClassRefreshCommand bcrCmd1 = (BeanClassRefreshCommand) cmd1;
                boolean found = false;
                for (Command cmd2: mergedCommands) {
                    BeanClassRefreshCommand bcrCmd2 = (BeanClassRefreshCommand) cmd2;
                    if (bcrCmd1 != bcrCmd2 && !bcrCmd1.beanClass.equals(bcrCmd2.beanClass) && bcrCmd2.beanClass.isAssignableFrom(bcrCmd1.beanClass)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    bcrCmd1.reloadBean(mergedCommands, oldSignatures);
                }
            }
            mergedCommands = popMergedCommands();
        } while (!mergedCommands.isEmpty());
    }

    private void recreateProxy(List<Command> mergedCommands) {
        if (isCreateEvent(mergedCommands) || isDeleteEvent(mergedCommands)) {
            LOGGER.trace("Skip OWB recreate proxy for create/delete event on class '{}'", className);
            return;
        }
        if (className != null) {
            try {
                LOGGER.debug("Executing ProxyRefreshAgent.recreateProxy('{}')", className);
                Class<?> agentClazz = Class.forName(ProxyRefreshAgent.class.getName(), true, appClassLoader);
                Method agentMethod  = agentClazz.getDeclaredMethod("recreateProxy",
                        new Class[] { ClassLoader.class,
                                      String.class,
                                      String.class
                        }
                );
                agentMethod.invoke(null,
                        appClassLoader,
                        className,
                       oldSignatureForProxyCheck
                );
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Plugin error, method not found", e);
            } catch (InvocationTargetException e) {
                LOGGER.error("Error recreateProxy class '{}' in classLoader '{}'", e, className, appClassLoader);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Plugin error, illegal access", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Plugin error, CDI class not found in classloader", e);
            }
        }
    }

    public void reloadBean(List<Command> mergedCommands, Map<String, String> oldSignatures) {
        if (isDeleteEvent(mergedCommands)) {
            LOGGER.trace("Skip OWB reload for delete event on class '{}'", className);
            return;
        }
        if (className != null) {
            try {
                LOGGER.debug("Executing BeanClassRefreshAgent.reloadBean('{}')", className);
                Class<?> agentClazz = Class.forName(BeanClassRefreshAgent.class.getName(), true, appClassLoader);
                Method agentMethod  = agentClazz.getDeclaredMethod("reloadBean",
                        new Class[] { ClassLoader.class,
                                      String.class,
                                      Map.class,
                                      String.class,
                                      URL.class
                        }
                );
                agentMethod.invoke(null,
                        appClassLoader,
                        className,
                        oldSignatures,
                        strBeanReloadStrategy,            // passed as String since BeanArchiveAgent has different classloader
                        beanArchiveUrl
                );
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Plugin error, method not found", e);
            } catch (InvocationTargetException e) {
                LOGGER.error("Error reloadBean class '{}' in classLoader '{}'", e, className, appClassLoader);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Plugin error, illegal access", e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Plugin error, CDI class not found in classloader", e);
            }
        }
    }

    /**
     * Check all merged events with same className for delete and create events. If delete without create is found, than assume
     * file was deleted.
     * @param mergedCommands
     */
    private boolean isDeleteEvent(List<Command> mergedCommands) {
        boolean createFound = false;
        boolean deleteFound = false;
        for (Command cmd : mergedCommands) {
            BeanClassRefreshCommand refreshCommand = (BeanClassRefreshCommand) cmd;
            if (className.equals(refreshCommand.className)) {
                if (refreshCommand.event != null) {
                    if (refreshCommand.event.getEventType().equals(FileEvent.DELETE))
                        deleteFound = true;
                    if (refreshCommand.event.getEventType().equals(FileEvent.CREATE))
                        createFound = true;
                }
            }
        }

        LOGGER.trace("isDeleteEvent result {}: createFound={}, deleteFound={}", createFound, deleteFound);
        return !createFound && deleteFound;
    }

    /**
     * Check all merged events with same className for create events.
     * @param mergedCommands
     */
    private boolean isCreateEvent(List<Command> mergedCommands) {
        boolean createFound = false;
        for (Command cmd : mergedCommands) {
            BeanClassRefreshCommand refreshCommand = (BeanClassRefreshCommand) cmd;
            if (className.equals(refreshCommand.className)) {
                if (refreshCommand.event != null) {
                    if (refreshCommand.event.getEventType().equals(FileEvent.CREATE))
                        createFound = true;
                }
            }
        }

        LOGGER.trace("isCreateEvent result {}: createFound={}", createFound);
        return createFound;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BeanClassRefreshCommand that = (BeanClassRefreshCommand) o;
        if (!appClassLoader.equals(that.appClassLoader)) return false;
        if (beanArchiveUrl != null && !beanArchiveUrl.equals(that.beanArchiveUrl)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = appClassLoader.hashCode();
        result = 31 * result + beanArchiveUrl.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "BeanClassRefreshCommand{" +
                "appClassLoader=" + appClassLoader +
                ", beanArchiveUrl=" + beanArchiveUrl +
                ", className='" + className + '\'' +
                '}';
    }
}
