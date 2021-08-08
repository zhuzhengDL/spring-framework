/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.context;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.lang.Nullable;

/**
 * Interface to be implemented by configurable web application contexts.
 * Supported by {@link ContextLoader} and
 * {@link org.springframework.web.servlet.FrameworkServlet}.
 *
 * <p>Note: The setters of this interface need to be called before an
 * invocation of the {@link #refresh} method inherited from
 * {@link org.springframework.context.ConfigurableApplicationContext}.
 * They do not cause an initialization of the context on their own.
 *
 * @author Juergen Hoeller
 * @since 05.12.2003
 * @see #refresh
 * @see ContextLoader#createWebApplicationContext
 * @see org.springframework.web.servlet.FrameworkServlet#createWebApplicationContext
 */
public interface ConfigurableWebApplicationContext extends WebApplicationContext, ConfigurableApplicationContext {

	/**
	 * Prefix for ApplicationContext ids that refer to context path and/or servlet name.
	 */
	String APPLICATION_CONTEXT_ID_PREFIX = WebApplicationContext.class.getName() + ":";

	/**
	 * Name of the ServletConfig environment bean in the factory.
	 * @see javax.servlet.ServletConfig
	 */
	String SERVLET_CONFIG_BEAN_NAME = "servletConfig";


	/**为此 Web 应用程序上下文设置 ServletContext。
	 * <p>不会引起上下文的初始化：在所有配置属性设置后需要调用refresh。
	 *
	 * Set the ServletContext for this web application context.
	 * <p>Does not cause an initialization of the context: refresh needs to be
	 * called after the setting of all configuration properties.
	 * @see #refresh()
	 */
	void setServletContext(@Nullable ServletContext servletContext);

	/** 为此 Web 应用程序上下文设置 ServletConfig。只调用属于特定 Servlet 的 WebApplicationContext。
	 *
	 * Set the ServletConfig for this web application context.
	 * Only called for a WebApplicationContext that belongs to a specific Servlet.
	 * @see #refresh()
	 */
	void setServletConfig(@Nullable ServletConfig servletConfig);

	/** 返回此 Web 应用程序上下文的 ServletConfig（如果有）。
	 * Return the ServletConfig for this web application context, if any.
	 */
	@Nullable
	ServletConfig getServletConfig();

	/** 设置此 Web 应用程序上下文的命名空间，用于构建默认上下文配置位置。根 Web 应用程序上下文没有命名空间。
	 *
	 * Set the namespace for this web application context,
	 * to be used for building a default context config location.
	 * The root web application context does not have a namespace.
	 */
	void setNamespace(@Nullable String namespace);

	/** 返回此 Web 应用程序上下文的命名空间（如果有）。
	 *
	 * Return the namespace for this web application context, if any.
	 */
	@Nullable
	String getNamespace();

	/** 以 init-param 样式设置此 Web 应用程序上下文的配置位置，即使用逗号、分号或空格分隔的不同位置。
	 <p>如果未设置，则实现应该根据需要使用给定命名空间或根 Web 应用程序上下文的默认值。

	 * Set the config locations for this web application context in init-param style,
	 * i.e. with distinct locations separated by commas, semicolons or whitespace.
	 * <p>If not set, the implementation is supposed to use a default for the
	 * given namespace or the root web application context, as appropriate.
	 */
	void setConfigLocation(String configLocation);

	/**设置此 Web 应用程序上下文的配置位置。
	 <p>如果未设置，则实现应该根据需要使用给定命名空间或根 Web 应用程序上下文的默认值。

	 * Set the config locations for this web application context.
	 * <p>If not set, the implementation is supposed to use a default for the
	 * given namespace or the root web application context, as appropriate.
	 */
	void setConfigLocations(String... configLocations);

	/**  返回此 Web 应用程序上下文的配置位置，如果未指定，则返回 {@code null}。
	 *
	 * Return the config locations for this web application context,
	 * or {@code null} if none specified.
	 */
	@Nullable
	String[] getConfigLocations();

}
