/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.config.spring.parsers.assembly;

import org.mule.config.spring.MuleHierarchicalBeanDefinitionParserDelegate;
import org.mule.config.spring.parsers.assembly.configuration.PropertyConfiguration;
import org.mule.config.spring.parsers.assembly.configuration.SingleProperty;
import org.mule.config.spring.parsers.assembly.configuration.SinglePropertyLiteral;
import org.mule.config.spring.parsers.collection.ChildListEntryDefinitionParser;
import org.mule.config.spring.parsers.collection.ChildMapEntryDefinitionParser;
import org.mule.config.spring.util.SpringXMLUtils;
import org.mule.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.MapFactoryBean;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.w3c.dom.Attr;

public class DefaultBeanAssembler implements BeanAssembler
{

    private static Log logger = LogFactory.getLog(DefaultBeanAssembler.class);
    private PropertyConfiguration beanConfig;
    private BeanDefinitionBuilder bean;
    private PropertyConfiguration targetConfig;
    private BeanDefinition target;

    public DefaultBeanAssembler(PropertyConfiguration beanConfig, BeanDefinitionBuilder bean,
                             PropertyConfiguration targetConfig, BeanDefinition target)
    {
        this.beanConfig = beanConfig;
        this.bean = bean;
        this.targetConfig = targetConfig;
        this.target = target;
    }

    public BeanDefinitionBuilder getBean()
    {
        return bean;
    }

    protected void setBean(BeanDefinitionBuilder bean)
    {
        this.bean = bean;
    }

    public BeanDefinition getTarget()
    {
        return target;
    }

    protected PropertyConfiguration getBeanConfig()
    {
        return beanConfig;
    }

    protected PropertyConfiguration getTargetConfig()
    {
        return targetConfig;
    }

    /**
     * Add a property defined by an attribute to the bean we are constructing.
     *
     * <p>Since an attribute value is always a string, we don't have to deal with complex types
     * here - the only issue is whether or not we have a reference.  References are detected
     * by explicit annotation or by the "-ref" at the end of an attribute name.  We do not
     * check the Spring repo to see if a name already exists since that could lead to
     * unpredictable behaviour.
     * (see {@link org.mule.config.spring.parsers.assembly.configuration.PropertyConfiguration})
     * @param attribute The attribute to add
     */
    public void extendBean(Attr attribute)
    {
        String oldName = SpringXMLUtils.attributeName(attribute);
        if (!beanConfig.isIgnored(oldName))
        {
            logger.debug(attribute + " for " + bean.getBeanDefinition().getBeanClassName());
            String oldValue = attribute.getNodeValue();
            String newName = bestGuessName(beanConfig, oldName, bean.getBeanDefinition().getBeanClassName());
            Object newValue = beanConfig.translateValue(oldName, oldValue);
            addPropertyWithReference(bean.getBeanDefinition().getPropertyValues(),
                    beanConfig.getSingleProperty(oldName), newName, newValue);
        }
    }

    /**
     * Allow direct access to bean for major hacks
     *
     * @param newName The property name to add
     * @param newValue The property value to add
     * @param isReference If true, a bean reference is added (and newValue must be a String)
     */
    public void extendBean(String newName, Object newValue, boolean isReference)
    {
        addPropertyWithReference(bean.getBeanDefinition().getPropertyValues(),
                new SinglePropertyLiteral(isReference), newName, newValue);
    }

    /**
     * Add a property defined by an attribute to the parent of the bean we are constructing.
     *
     * <p>This is unusual.  Normally you want {@link #extendBean(org.w3c.dom.Attr)}.
     * @param attribute The attribute to add
     */
    public void extendTarget(Attr attribute)
    {
        String oldName = SpringXMLUtils.attributeName(attribute);
        String oldValue = attribute.getNodeValue();
        String newName = bestGuessName(targetConfig, oldName, bean.getBeanDefinition().getBeanClassName());
        Object newValue = targetConfig.translateValue(oldName, oldValue);
        addPropertyWithReference(target.getPropertyValues(),
                targetConfig.getSingleProperty(oldName), newName, newValue);
    }

    /**
     * Allow direct access to target for major hacks
     *
     * @param newName The property name to add
     * @param newValue The property value to add
     * @param isReference If true, a bean reference is added (and newValue must be a String)
     */
    public void extendTarget(String newName, Object newValue, boolean isReference)
    {
        assertTargetPresent();
        addPropertyWithReference(target.getPropertyValues(),
                new SinglePropertyLiteral(isReference), newName, newValue);
    }
    
    /**
     * Insert the bean we have built into the target (typically the parent bean).
     *
     * <p>This is the most complex case because the bean can have an aribtrary type.
     * 
     * @param oldName The identifying the bean (typically element name).
     */
    public void insertBeanInTarget(String oldName)
    {
        logger.debug("insert " + bean.getBeanDefinition().getBeanClassName() + " -> " + target.getBeanClassName());
        assertTargetPresent();
        String beanClass = bean.getBeanDefinition().getBeanClassName();
        PropertyValues sourceProperties = bean.getRawBeanDefinition().getPropertyValues();
        String newName = bestGuessName(targetConfig, oldName, target.getBeanClassName());
        MutablePropertyValues targetProperties = target.getPropertyValues();
        PropertyValue pv = targetProperties.getPropertyValue(newName);
        Object oldValue = null == pv ? null : pv.getValue();

        if (! targetConfig.isIgnored(oldName))
        {
            if (targetConfig.isCollection(oldName) ||
                    beanClass.equals(ChildListEntryDefinitionParser.ListEntry.class.getName()))
            {
                if (null == oldValue)
                {
                    if (beanClass.equals(ChildMapEntryDefinitionParser.KeyValuePair.class.getName()) ||
                            beanClass.equals(MapEntryCombiner.class.getName()) ||
                            beanClass.equals(MapFactoryBean.class.getName()))
                    {
                        // a collection of maps requires an extra intermediate object that does the
                        // lazy combination/caching of maps when first used
                        BeanDefinitionBuilder combiner = BeanDefinitionBuilder.rootBeanDefinition(MapCombiner.class);
                        targetProperties.addPropertyValue(newName, combiner.getBeanDefinition());
                        MutablePropertyValues combinerProperties = combiner.getBeanDefinition().getPropertyValues();
                        oldValue = new ManagedList();
                        pv = new PropertyValue(MapCombiner.LIST, oldValue);
                        combinerProperties.addPropertyValue(pv);
                    }
                    else
                    {
                        oldValue = new ManagedList();
                        pv = new PropertyValue(newName, oldValue);
                        targetProperties.addPropertyValue(pv);
                    }
                }

                List list = retrieveList(oldValue);
                if (ChildMapEntryDefinitionParser.KeyValuePair.class.getName().equals(beanClass))
                {
                    list.add(new ManagedMap());
                    retrieveMap(list.get(list.size() - 1)).put(
                            sourceProperties.getPropertyValue(ChildMapEntryDefinitionParser.KEY).getValue(),
                            sourceProperties.getPropertyValue(ChildMapEntryDefinitionParser.VALUE).getValue());
                }
                else if (beanClass.equals(ChildListEntryDefinitionParser.ListEntry.class.getName()))
                {
                    list.add(sourceProperties.getPropertyValue(ChildListEntryDefinitionParser.VALUE).getValue());
                }
                else
                {
                    list.add(bean.getBeanDefinition());
                }
            }
            else
            {
                // not a collection

                if (ChildMapEntryDefinitionParser.KeyValuePair.class.getName().equals(beanClass))
                {
                    if (null == pv || null == oldValue)
                    {
                        pv = new PropertyValue(newName, new ManagedMap());
                        targetProperties.addPropertyValue(pv);
                    }
                    retrieveMap(pv.getValue()).put(
                            sourceProperties.getPropertyValue(ChildMapEntryDefinitionParser.KEY).getValue(),
                            sourceProperties.getPropertyValue(ChildMapEntryDefinitionParser.VALUE).getValue());
                }
                else
                {
                    targetProperties.addPropertyValue(newName, bean.getBeanDefinition());
                }
            }
        }
    }

    private static List retrieveList(Object value)
    {
        if (value instanceof List)
        {
            return (List) value;
        }
        else if (isDefinitionOf(value, MapCombiner.class))
        {
            return (List) unpackDefinition(value, MapCombiner.LIST);
        }
        else
        {
            throw new ClassCastException("Collection not of expected type: " + value);
        }
    }

    private static Map retrieveMap(Object value)
    {
        if (value instanceof Map)
        {
            return (Map) value;
        }
        else if (isDefinitionOf(value, MapFactoryBean.class))
        {
            return (Map) unpackDefinition(value, "sourceMap");
        }
        else
        {
            throw new ClassCastException("Map not of expected type: " + value);
        }
    }

    private static boolean isDefinitionOf(Object value, Class clazz)
    {
        return value instanceof BeanDefinition &&
                ((BeanDefinition) value).getBeanClassName().equals(clazz.getName());
    }

    private static Object unpackDefinition(Object definition, String name)
    {
        return ((BeanDefinition) definition).getPropertyValues().getPropertyValue(name).getValue();
    }


    /**
     * Copy the properties from the bean we have been building into the target (typically
     * the parent bean).  In other words, the bean is a facade for the target.
     *
     * <p>This assumes that the source bean has been constructed correctly (ie the decisions about
     * what is ignored, a map, a list, etc) have already been made.   All it does (apart from a
     * direct copy) is merge collections with those on the target when necessary.
     */
    public void copyBeanToTarget()
    {
        logger.debug("copy " + bean.getBeanDefinition().getBeanClassName() + " -> " + target.getBeanClassName());
        assertTargetPresent();
        MutablePropertyValues targetProperties = target.getPropertyValues();
        MutablePropertyValues beanProperties = bean.getBeanDefinition().getPropertyValues();
        for (int i=0;i < beanProperties.size(); i++)
        {
            PropertyValue propertyValue = beanProperties.getPropertyValues()[i];
            addPropertyWithoutReference(targetProperties, new SinglePropertyLiteral(),
                    propertyValue.getName(), propertyValue.getValue());
        }
    }

    public void setBeanFlag(String flag)
    {
        MuleHierarchicalBeanDefinitionParserDelegate.setFlag(bean.getRawBeanDefinition(), flag);
    }

    protected void assertTargetPresent()
    {
        if (null == target)
        {
            throw new IllegalStateException("Bean assembler does not have a target");
        }
    }

    protected void addPropertyWithReference(MutablePropertyValues properties, SingleProperty config,
                                            String name, Object value)
    {
        if (!config.isIgnored())
        {
            if (config.isReference())
            {
                if (value instanceof String)
                {
                    if (((String) value).trim().indexOf(" ") > -1)
                    {
                        config.setCollection();
                    }
                    for (StringTokenizer refs = new StringTokenizer((String) value); refs.hasMoreTokens();)
                    {
                        String ref = refs.nextToken();
                        if (logger.isDebugEnabled())
                        {
                            logger.debug("possible non-dependent reference: " + name + "/" + ref);
                        }
                        addPropertyWithoutReference(properties, config, name, new RuntimeBeanReference(ref));
                    }
                }
                else
                {
                    throw new IllegalArgumentException("Bean reference must be a String: " + name + "/" + value);
                }
            }
            else
            {
                addPropertyWithoutReference(properties, config, name, value);
            }
        }
    }

    protected void addPropertyWithoutReference(MutablePropertyValues properties, SingleProperty config,
                                               String name, Object value)
    {
        if (!config.isIgnored())
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(name + ": " + value);
            }
            Object oldValue = null;
            if (properties.contains(name))
            {
                oldValue = properties.getPropertyValue(name).getValue();
            }
            // merge collections
            if (config.isCollection() || oldValue instanceof Collection || value instanceof Collection)
            {
                Collection values = new ManagedList();
                if (null != oldValue)
                {
                    properties.removePropertyValue(name);
                    if (oldValue instanceof Collection)
                    {
                        values.addAll((Collection) oldValue);
                    }
                    else
                    {
                        values.add(oldValue);
                    }
                }
                if (value instanceof Collection)
                {
                    values.addAll((Collection) value);
                }
                else
                {
                    values.add(value);
                }
                properties.addPropertyValue(name, values);
            }
            else
            {
                properties.addPropertyValue(name, value);
            }
        }
    }

    protected static String bestGuessName(PropertyConfiguration config, String oldName, String className)
    {
        String newName = config.translateName(oldName);
        if (! methodExists(className, newName))
        {
            String plural = newName + "s";
            if (methodExists(className, plural))
            {
                // this lets us avoid setting addCollection in the majority of cases
                config.addCollection(oldName);
                return plural;
            }
            if (newName.endsWith("y"))
            {
                String pluraly = newName.substring(0, newName.length()-1) + "ies";
                if (methodExists(className, pluraly))
                {
                    // this lets us avoid setting addCollection in the majority of cases
                    config.addCollection(oldName);
                    return pluraly;
                }
            }
        }
        return newName;
    }

    protected static boolean methodExists(String className, String newName)
    {
        try
        {
            // is there a better way than this?!
            // BeanWrapperImpl instantiates an instance, which we don't want.
            // if there really is no better way, i guess it should go in
            // class or bean utils.
            Class clazz = ClassUtils.getClass(className);
            Method[] methods = clazz.getMethods();
            String setter = "set" + newName;
            for (int i = 0; i < methods.length; ++i)
            {
                if (methods[i].getName().equalsIgnoreCase(setter))
                {
                    return true;
                }
            }
        }
        catch (Exception e)
        {
            logger.debug("Could not access bean class " + className, e);
        }
        return false;
    }

}
