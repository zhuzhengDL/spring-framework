/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.web.servlet.handler;

import java.util.ArrayList;
import java.util.List;

import org.springframework.util.StringUtils;

/**
 * Implementation of the {@link org.springframework.web.servlet.HandlerMapping}
 * interface that maps from URLs to beans with names that start with a slash ("/"),
 * similar to how Struts maps URLs to action names.
 *
 * <p>This is the default implementation used by the
 * {@link org.springframework.web.servlet.DispatcherServlet}, along with
 * {@link org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping}.
 * Alternatively, {@link SimpleUrlHandlerMapping} allows for customizing a
 * handler mapping declaratively.
 *
 * <p>The mapping is from URL to bean name. Thus an incoming URL "/foo" would map
 * to a handler named "/foo", or to "/foo /foo2" in case of multiple mappings to
 * a single handler.
 *
 * <p>Supports direct matches (given "/test" -&gt; registered "/test") and "*"
 * matches (given "/test" -&gt; registered "/t*"). Note that the default is
 * to map within the current servlet mapping if applicable; see the
 * {@link #setAlwaysUseFullPath "alwaysUseFullPath"} property for details.
 * For details on the pattern options, see the
 * {@link org.springframework.util.AntPathMatcher} javadoc.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see SimpleUrlHandlerMapping
 */
public class BeanNameUrlHandlerMapping extends AbstractDetectingUrlHandlerMapping {

	/**
	 * 检查是否bean的名字是否是/ 开头，如果是 就当成 url对应的一个处理器（controller）
	 * Checks name and aliases of the given bean for URLs, starting with "/".
	 */
	@Override
	protected String[] determineUrlsForHandler(String beanName) {
		List<String> urls = new ArrayList<>();
		// 如果是以 / 开头，添加到 urls
		if (beanName.startsWith("/")) {
			urls.add(beanName);
		}
		String[] aliases = obtainApplicationContext().getAliases(beanName);
		// 获得 beanName 的别名们，如果以 / 开头，则添加到 urls
		for (String alias : aliases) {
			if (alias.startsWith("/")) {
				urls.add(alias);
			}
		}
		// 返回
		return StringUtils.toStringArray(urls);
	}

}
