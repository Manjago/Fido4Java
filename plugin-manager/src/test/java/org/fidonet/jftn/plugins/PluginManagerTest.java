package org.fidonet.jftn.plugins;

import junit.framework.TestCase;
import org.fidonet.jftn.plugins.mock.*;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * Author: Vladimir Kravets
 * E-Mail: vladimir.kravets-ukr@hp.com
 * Date: 8/7/13
 * Time: 1:36 PM
 */
public class PluginManagerTest {
    
    @Test
    public void testDependenciesSortList() {
        
        PluginManager manager =  PluginManager.getInstance();
        //     private Collection<? extends Plugin> sortDependencies(Collection<? extends Plugin> plugins) {
        List<Plugin> plugins = new ArrayList<Plugin>();
        plugins.add(new PluginA());
        plugins.add(new PluginB());
        plugins.add(new PluginC());
        plugins.add(new PluginD());
        plugins.add(new PluginE());
        plugins.add(new PluginF());
        plugins.add(new PluginG());
        
        try {
            Method sort = manager.getClass().getDeclaredMethod("sortDependencies", Collection.class);
            sort.setAccessible(true);
            Collection<? extends Plugin> sortPlugins = (Collection<? extends Plugin>) sort.invoke(manager, plugins);
            StringBuilder actual = new StringBuilder();
            for (Plugin plugin : sortPlugins) {
                actual.append(plugin.getPluginInfo().getId());
            }
            TestCase.assertEquals("GBCDFEA", actual.toString());
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
            TestCase.fail();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            TestCase.fail();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            TestCase.fail();
        }
    }
    
}
