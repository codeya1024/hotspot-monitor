# Hotspot Monitor — HTTP 请求卡点监控（类 XRebel）

一套「轻量 Java Agent + IntelliJ IDEA 插件」：零侵入地监控你的 Java Web 服务的

HTTP 请求耗时分布，直接定位卡点在哪一层 —— **SQL 慢查询 / 下游 HTTP 调用 / 整体请求分布**。

支持 **JDK 8 / 21 / 25**，同时兼容 **javax.servlet（Boot 2 / 老项目）** 与

**jakarta.servlet（Boot 3 / 新项目）** 双栈。全部用 **Maven** 构建。



```
┌────────────────────────────┐      ┌──────────────────────────────┐
│  被监控服务（-javaagent）    │      │  IntelliJ IDEA 2026.2 插件     │
│  ┌────────────────────────┐ │      │  ┌────────────────────────┐  │
│  │ Servlet 入口插桩        │ │      │  │ 请求列表 + 瀑布图(卡点)   │  │
│  │ JDBC 插桩（SQL 耗时）    │ │      │  │ 慢 SQL / 慢下游 HTTP     │  │
│  │ RestTemplate/OkHttp 插桩│ │      │  │ 端点统计（P50/P95）      │  │
│  └───────────┬────────────┘ │      │  └───────────▲────────────┘  │
│              │ 环形缓冲+聚合  │      │              │ 轮询           │
│   127.0.0.1:28765 HTTP 上报 ─┼──────┼──▶ /api/snapshot            │
│   进程注册表（pid→端口）──────┼──────┼──▶ 自动发现                 │
└────────────────────────────┘      └──────────────────────────────┘
```

## 工程结构



| 模块       | 说明                                                         |
| -------- | ---------------------------------------------------------- |
| `agent`  | Java Agent：Byte Buddy 字节码插桩 + 数据汇聚 + 本地上报服务（Java 8 字节码）    |
| `plugin` | IntelliJ Platform 插件（since-build 262 / IDEA 2026.2）：工具窗口展示 |
| `demo`   | Spring Boot 3 演示服务，用于端到端验证                                 |

## 快速开始

### 1. 构建（Maven）



```
# Agent fat-jar（含 Byte Buddy，清单含 Premain-Class；编译为 Java 8 字节码，可挂 JDK 8/21/25）
mvn -pl agent package

# IDEA 插件包
# 注意：插件模块直接用你本机 IDEA 的 lib 编译（plugin/pom.xml 的 idea.lib.dir，
#       默认 /Applications/IntelliJ IDEA 2.app/Contents/lib = 2026.2），
#       IDEA 2026.2 平台是 Java 25 字节码，构建时需 JDK ≥ 25：
JAVA_HOME=/path/to/jdk-25 mvn -pl plugin package
```

产物：



* `agent/target/hotspot-agent.jar`

* `plugin/target/hotspot-monitor.zip`（内含 `hotspot-monitor/lib/hotspot-monitor.jar`）

### 2. 给服务挂上 agent（不改一行业务代码）



```
java -javaagent:/path/to/hotspot-agent.jar -jar your-service.jar
```

启动后控制台会打印：



```
[hotspot-agent] premain OK (...) reportPort=28765 slowSqlMs=200ms slowHttpMs=300ms
```

### 3. 打开插件看卡点

IDEA → `Preferences → Plugins → ⚙️ → Install Plugin from Disk...` 选择

`plugin/target/hotspot-monitor.zip` 安装并重启，右侧出现 **Hotspot Monitor** 工具窗口：



* **Agent 下拉框**：自动发现本机已挂 agent 的进程（从注册表读取），选一个点「连接」；

  也可以手动填端口连接

* **请求列表**：实时请求 + 状态码 + 总耗时 + SQL/HTTP 明细

* **瀑布图**：选中某条请求，展示 方法（绿）/ SQL（橙）/ 下游 HTTP（蓝）/ 异常（红）卡点；

  面板本身**可选中复制**（框选 + Ctrl+C，或右键复制 / 复制全部），每行含相对请求起点的

  时刻偏移（`[+12.3ms]`）与 SQL/HTTP 的业务调用来源（`← DemoApplication.sql(80)`）

* **方法**：选中请求的方法耗时聚合（自身 ms / 总 ms / 次数 / 最大 ms，按自身耗时倒序）

* **慢 SQL / 慢下游 HTTP**：超过阈值的调用聚合（次数 / 总耗时 / 最大耗时 / 最近调用链）

* **端点统计**：每个端点的 count /avg/ P50 / P95 /max

* **后台片段**：无请求上下文时的 SQL/HTTP（后台任务、线程池等）

* **表格复制 / 排序**：所有表格支持 `Ctrl+C` / 右键「复制」（复制单元格显示文本）；

  耗时列按数字排序（点击列头），时间列按时间戳排序

* **清空**：工具栏「清空」按钮重置当前 agent 的监控数据（`POST /api/clear`），重新记录

## Agent 配置（-D 系统属性或 -javaagent 参数 k=v）



| 配置                   | 默认      | 说明                          |
| -------------------- | ------- | --------------------------- |
| `hotspot.port`       | `28765` | 本地上报端口；`0` = 随机；被占用自动避让 +1  |
| `hotspot.ring`       | `500`   | 请求环形缓冲条数                    |
| `hotspot.slowSqlMs`  | `200`   | 慢 SQL 阈值                    |
| `hotspot.slowHttpMs` | `300`   | 慢下游 HTTP 阈值                 |
| `hotspot.packages`   | 自动取主类包名 | 方法级计时的类名前缀（逗号分隔）；空 = 关闭方法计时 |

示例：



```
java -javaagent:hotspot-agent.jar="slowSqlMs=100,slowHttpMs=200" -Dhotspot.port=29000 -jar app.jar
```

> 方法计时默认只覆盖主类所在包（如 
>
> `com.xxx.app`
>
> ），可用
> `-Dhotspot.packages=com.xxx.service,com.xxx.mapper`
>
>  缩小 / 指定范围；
> 设空串 
>
> `-Dhotspot.packages=`
>
>  可关闭方法计时（全量插桩有少量运行时开销，仅开发调试期使用）。

## 插桩点



| 目标                                                                                                                                   | 说明                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------- |
| `javax.servlet.http.HttpServlet.service`                                                                                             | Boot 2 / 老项目请求入口                                               |
| `jakarta.servlet.http.HttpServlet.service`                                                                                           | Boot 3 请求入口                                                    |
| `java.sql.Connection/Statement` 实现类                                                                                                  | prepareStatement /execute\*（预编译语句也能拿到 SQL）                     |
| `org.springframework.web.client.RestTemplate.doExecute`                                                                              | Spring 下游 HTTP（4 参与 5 参重载都覆盖，Spring 6.1+ 实际走 5 参）              |
| `okhttp3.internal.connection.RealCall.execute`                                                                                       | OkHttp 4.x 同步调用（3.x 的 `okhttp3.RealCall` 不存在）                  |
| `org.apache.http.impl.client.CloseableHttpClient.execute`（4.x）/ `org.apache.hc.client5.impl.classic.InternalHttpClient.execute`（5.x） | Apache HttpClient 下游（2 参重载）                                    |
| `org.springframework.web.reactive.function.client.ExchangeFunctions$DefaultExchangeFunction.exchange`                                | Spring WebClient 下游（异步 Mono 完成回调）                              |
| `hotspot.packages` 包内全部方法                                                                                                            | **方法级计时**（非构造 / 非抽象方法）：请求内聚合 自身耗时 / 总耗时 / 次数 / 最大耗时，并输出方法时间线事件 |

> 常见连接池（Hikari / Druid / DBCP / Tomcat / c3p0 / UCP）的代理类会被排除，只对底层 JDBC 驱动插桩，避免同一 SQL 重复计数。
>
> **每条 SQL / 下游 HTTP 都会捕获业务调用来源**：来源取自插桩维护的方法执行栈（参考 XRebel 思路——请求上下文内就地维护调用栈，SQL/HTTP 发生时栈顶即「正在执行它的方法」），因此天然只含 `hotspot.packages` 内的业务方法，**不会出现 $Proxy / MyBatis 拦截器 / Druid 过滤器这类框架噪音帧**；未配置 `hotspot.packages` 时回退为线程栈快照过滤（此时调用链可能含框架帧）。瀑布图每行、慢 SQL 标签页的「调用链」列均可见。

## 已知限制



* **跨线程异步调用**（线程池 / CompletableFuture 里的 SQL、HTTP、方法）不会挂到原请求上，会出现在「后台片段」标签；方法计时同样只统计请求线程内的调用
* **JDK 原生 `java.net.http.HttpClient`** 暂未插桩（RestTemplate / OkHttp / Apache HttpClient / WebClient 已覆盖主流路径）
* **OkHttp 异步 enqueue** 未插桩，只覆盖同步 execute
* **方法计时只覆盖 `hotspot.packages` 内的类**：框架 / 容器 / 依赖包的方法不计时（避免性能爆炸），「方法慢但 SQL 快」的定位请把业务包加入 `hotspot.packages`
* 方法计时 advice 不使用 `skipOn` 优化（已验证其与对象返回值搭配会改变目标方法行为，见「构建踩坑记录」），改为 exit 内一次判空，开销可忽略

## 验证



```
# 1. Agent 插桩测试（真实挂载到测试 JVM：双 Servlet 栈 / H2 JDBC / RestTemplate / OkHttp / JSON / 方法计时）
mvn -pl agent test   # 期望 10 个测试全绿

# 2. 端到端演示（端口 18080，自动挂 agent；exec:java 无法挂 agent，必须用 exec:exec）
mvn -pl demo exec:exec
```

然后（另开终端）：



```
curl -s localhost:18080/hello
curl -s localhost:18080/sql        # 慢 SQL（GROUP BY 100 万行，~300ms+ 超过默认阈值）
curl -s localhost:18080/slow       # 业务逻辑卡点（sleep 400ms，方法自身耗时可见）
curl -s localhost:18080/downstream # 下游 RestTemplate + OkHttp 打 /slow（~450ms，超过慢 HTTP 阈值）
curl -s localhost:18080/error
curl -s localhost:28765/api/snapshot | python3 -m json.tool
```

快照里应能看到：请求列表（带 SQL/HTTP 子片段 **及业务调用链 caller**）、方法统计

（`methods[]`：自身 / 总耗时 / 次数）、方法时间线（`methodEvents[]`）、端点统计（count/avg/P50/P95/max）、

`slowSqls`（慢 SQL 聚合，含最近调用链 `lastCaller`）、`slowHttps`（慢下游 HTTP 聚合）。

## 双栈说明（javax + jakarta）



* agent 主代码按 **Java 8 字节码**编译（`maven-compiler-plugin` 的 `release=8`），

  运行时不受被监控服务 JDK 版本限制（8/21/25 均可）。

* 两个 Servlet advice 分别匹配 `javax.servlet.http.HttpServlet` 与

  `jakarta.servlet.http.HttpServlet`；服务里只有对应栈的类会被插桩。

* servlet-api /okhttp 均为 `provided` 依赖：只参与编译与测试，**不会**打进 agent jar，

  运行时由被监控应用自己的类加载器提供，避免污染应用类路径。

## 构建踩坑记录（Maven 迁移后）



* **advice 内联的访问权限**：Byte Buddy 把 advice 方法体内联进目标类时，被调用的

  辅助方法（如 `truncate`）必须是 `public`，否则运行期 `IllegalAccessError` 且被

  advice 的 try/catch 吞掉（表现为 “插桩静默失效”）。

* **OkHttp 的&#x20;**`@Advice.This`：不要写带类型的 `@Advice.This RealCall`，否则变换时会

  触发对正在加载的 RealCall 的嵌套加载（`LinkageError: duplicate class definition`）；

  用 `@Advice.This Object` 再在方法体内强转。

* **Spring 6.1+ 的 RestTemplate**：`doExecute` 有 4 参与 5 参两个重载，4 参 public

  `execute` 实际转发到 5 参版本，需两个 advice 都注册。

* **插件编译**：IDEA 2026.2 平台 jar 为 Java 25 字节码且高度模块化（437 个 lib jar），

  javac 的 `dir/*` 通配符在 maven-compiler-plugin 拼接出的复合 classpath 下不会展开，

  因此插件模块用 `maven-antrun-plugin` 显式展开 lib 目录后驱动 javac，构建需 JDK ≥ 25；

  2026.2 已移除 `com.intellij.ui.components.JBTable`，UI 使用标准 Swing 组件。

* **方法 advice 禁用&#x20;**`skipOn`：`@Advice.OnMethodEnter(skipOn=OnNonDefaultValue)` 与对象

  返回值搭配时会把**目标方法体整个跳过**（表现为方法耗时～µs 级、sleep 不生效），

  原因见 byte-buddy `OnMethodEnter#skipOn` 语义；正确做法是 enter 正常返回对象、

  exit 里判空，开销仅一次判空。

* **方法计时包名**：`-Dhotspot.packages` 建议只填业务包前缀；注意 agent 自身

  `com.codeya.hotspot.monitor.agent` 与 `java.*`/`jdk.*` 永远排除，避免递归插桩。

## 开源说明（License / 已知限制）



* **License**：Apache License 2.0（见根目录 LICENSE）。可自由使用、修改、分发，但需保留版权声明；不提供任何担保。

* **已知限制 / 待改进**（欢迎 PR）：


  * 快照为全量序列化（请求 → 方法树全量 + spans + 聚合），超大请求（数十万方法节点）时上报 payload 与插件渲染有压力；后续可做截断 / 采样。

  * 慢 SQL / 慢下游 HTTP 聚合以内容为 key 并截断输出 100 条，长期运行基数大时可考虑 LRU。

  * 方法计时默认只覆盖 `hotspot.packages` 推导的应用包（排除常见框架包），见上方「插桩范围与噪音过滤」。

  * 异步线程（如 WebClient 回调）不关联请求上下文，相关 span 落入「无主 SQL/HTTP」；这是有意的设计取舍。

  * 本地上报服务只监听 127.0.0.1、无鉴权：同一机器上的其他进程可读取监控数据，勿用于含敏感数据的共享主机。

## 表格单元格选中与复制（2026-09-30）



* 请求表保持行选择（点击行看详情），五个明细表（慢 SQL / 慢下游 HTTP / 端点统计 / 无主 SQL/HTTP/ 方法）改为**单元格选择**：单击即选中单个单元格，⌘C / Ctrl+C 复制该格。

* 所有表格新增**双击单元格 = 复制该格内容**。

* 右键菜单增强：**复制单元格 / 复制选中 / 复制整行 / 复制整表**（整表含表头，TSV）。

* 复制内容取单元格渲染后文本（与界面所见一致，含等宽格式化）。

## 补下游 HTTP：Apache HttpClient / Spring WebClient（2026-09-30）



* 新增 `ApacheHttpClientAdvice`：织入 4.x `CloseableHttpClient.execute`（父类实现）与 5.x `InternalHttpClient.execute` 的 2 参重载；label 反射取 `getMethod()+getUri/getURI`，advice 参数全 Object 零外部依赖。

* 新增 `WebClientAdvice`：织入 `ExchangeFunction.exchange(ClientRequest)`（实现类 `ExchangeFunctions$DefaultExchangeFunction`，所有 WebClient 请求必经）；`@Advice.Return(readOnly=false)` 替换返回 Mono 挂 `doOnSuccess/doOnError` 回调（耗时 = enter 到 Mono 完成）；回调用显式嵌套类（Advice 复制字节码不支持 invokedynamic）；agent 仅 provided reactor-core。

* 本轮踩坑（已修复）：

1. `takesArgument(0, nameStartsWith(...))` 的 matcher 是 `ElementMatcher<CharSequence>`，对 `TypeDescription` 运行期不匹配 → 方法静默不织入（类型却 transform 成功）。改用 `takesArgument(0, named("...ClientRequest"))`。

2. **advice 辅助成员可见性**：织入字节码被复制进目标类（跨包调用），包私有辅助方法 / 嵌套类 / 构造函数 → `IllegalAccessError`（被 advice 的 catch 吞掉，表现为 "span 不记录"）。**凡被织入方法体引用的辅助方法与嵌套类必须 public**。

3. 反射 label 遇包私有实现类（`BodyInserterRequest`）invoke 抛 `IllegalAccessException` → `setAccessible(true)`。

4. 4.x 的 `execute` 实现在父类 `CloseableHttpClient`（子类只实现 doExecute）；只插 2 参重载避免同请求记两次。

* 单测：`ApacheHttpClientTest`（真实 HttpClients.execute）1/1、`WebClientTest`（WebClient GET + block）1/1；全量 agent 测试回归 0 失败。

* demo 新增 `/apache`、`/webclient` 端点；端到端验证（JDK25 premain 挂载）：`/apache` 请求内 HTTP span 436ms ✓；`/webclient` 异步回调落孤儿池 span 640ms ✓（Reactor 线程无请求上下文 → 无主 HTTP，符合设计）。

## 下拉框切换联动端口框（2026-09-30）



* 现象：手动切换 Agent 下拉框时，端口输入框不变（实际连接用的端口是对的 ——

  resolvePort 优先取下拉框选中项的 port，纯显示不联动）；

* 修复：agentCombo 加 ActionListener，切换选项时端口框同步显示该 agent 的端口；

* 仅插件改动，替换 zip 解压后重启 IDEA。

## 插件版本号（2026-09-30）



* plugin.xml 增加 `<version>1.0</version>`（IDEA 设置 > 插件 中可见，为后续上架

  JetBrains Marketplace 做准备）；补 `<change-notes>`（发布说明）。

* **以后发版流程**：改 `plugin/src/main/resources/META-INF/plugin.xml` 的 `<version>`

  为 1.1、1.2…（与 change-notes 同步更新）→ `mvn -pl plugin package` →

  上传 zip 到 [https://plugins.jetbrains.com/](https://plugins.jetbrains.com/) 发布。

* 验证：zip 内 jar 的 plugin.xml 版本 = 1.0。

## 全部表格 / 方法树统一等宽字体（2026-09-30）



* 现象：无主 SQL/HTTP 等 tab 里固定 12 位的时间戳（HH:mm:ss.SSS）视觉上对不齐 ——

  JTable 默认比例字体下 '1' 比 '6'/'8' 窄，等长字符串 ≠ 等宽；

* 改法：MainPanel 6 张表（请求 / 慢 SQL / 慢 HTTP / 端点 / 无主 / 方法）统一

  `Font.MONOSPACED 12px`；WaterfallPanel 方法树节点渲染器也改用等宽

  （与 timeline/header 已有的 MONO 一致）—— 耗时、次数、时间戳逐列对齐；

* 仅插件改动，替换 zip 解压后重启 IDEA。

## 依赖重定位：agent 与业务 jar 零冲突（2026-09-30）



* **byte-buddy relocate 到&#x20;**`com.codeya.hotspot.monitor.agent.shaded.bytebuddy`：agent jar 里不再出现

  顶层 `net/bytebuddy`（验证残留 0）。业务应用自身若也依赖 byte-buddy（版本可能不同），

  与 agent 各用各的，同一 classloader 下不再有 "谁先加载谁生效" 的版本冲突

  （NoSuchMethodError / NoClassDefFoundError 风险消除）。

* 同时排除 byte-buddy 的 `META-INF/versions/**` multi-release 条目

  （shade 3.5.3 的 ASM 读不了 class 68=Java24；JDK8/21 本就不读该条目，

  JDK25 回退基类实现，功能无损）。

* **隔离矩阵**（agent 与业务不冲突的完整机制）：


  * 自身代码用私有命名空间 `com.codeya.hotspot.monitor.agent.*`；

  * 唯一第三方运行时依赖 byte-buddy 已重定位；

  * `-javaagent` 机制本身：业务 pom/classpath 完全感知不到 agent；

  * 插桩只发生在匹配类加载时（ClassFileTransformer），不影响其他类；

  * 与业务自身的 MyBatis Interceptor / Spring Filter 各层独立；

  * IDEA 插件在 IDEA 进程（不同 JVM），且不打包第三方依赖（用 IDEA 自带 lib）。

* 注意：shade 重写字节码需能读 class 68，打包 agent 需用 **JDK25** 环境变量。

* 验证：jar 结构检查（net/bytebuddy 0 残留、3010 个 shaded 类）；

  demo 端到端 premain OK、SQL / 方法树 / 慢 SQL 采集正常。

## 后台 SQL/HTTP tab：孤儿 span 带调用来源（2026-09-30）



* "后台片段" tab 更名 **"无主 SQL/HTTP"**（避免被理解成 "选中请求里的 SQL/HTTP"）：

  这里展示**不属于任何请求的孤儿 span**

  （异步线程 / 定时任务 / 后台线程池里执行的 SQL 与下游 HTTP，无法归属到任何请求）；

* 新增 **调用来源** 列：显示该 SQL/HTTP 从哪个方法发起（如

  `xxxMapper.find(34) ← DataSplitBaseUtil.splitGet(120)`），

  一眼定位是哪个后台任务在跑；未知来源显示 "(未知线程)"。

* agent：orphanSpans 序列化补 caller 字段（agent 有改动，替换 jar 后**重启服务**）；

  plugin：列 + tab 名（替换 zip 解压后**重启 IDEA**）。

* 新增 **发生时间** 列（HH:mm:ss.SSS）：每条孤儿 span 记录发生时刻（epoch ms），

  列表按最新在前排列，一眼看到 "刚才 / 多久前" 发生的无主调用。

* agent：ChildSpan 增 occurredAtMs（wall-clock）；orphanSpans 序列化带 atMs。

* 验证：agent 测试通过、打包成功；plugin mvn package 成功。

## 瀑布图布局：时间线 / 方法树各自滚动，方法栈默认上移（2026-09-30）



* 瀑布图 tab 恢复上下分栏（JSplitPane），SQL 时间线表格、方法树**各自独立滚动条**，

  互不挤占；

* 分隔条默认上移到 30%（上半 30% 时间线 / 下半 70% 方法树）——

  **方法栈默认可见，不用再拖滚动条到底**；分隔条仍可拖动自定义比例；

* 去掉了瀑布图 tab 的外层 JScrollPane 包裹（原布局外层滚动 + 内层 split 双重滚动，

  方法树被推到视口外），split 直接占满 tab。

* 验证：JDK25 + IDEA lib 全量 javac 0 错误；mvn package 成功。

* 仅插件改动，替换 zip 解压后**重启 IDEA** 生效（agent 与用户服务无需动）。

## 高频小调用折叠 + 不可归因自身耗时提示（2026-09-30）



* **高频小调用不再静默消失**。噪音过滤从 agent 移到插件：


  * agent 的 methodTree **全量输出**（不再丢弃单次 < 阈值且无 SQL 的子树），并输出

    `thresholds.noiseThresholdMs` 与每个方法节点的 `hasSql`；

  * 插件把这类高频小调用（如 `LimitResultSetFilter.resultSet_next ×13073`、

    响应写入 `write ×900万`）折叠为一个可展开的占位行：

    `已过滤 N 个高频子调用 · 合计 Xms · 单次均值 Y · 点击展开`——

    展开可见每个被过滤方法的 × 次数 / 合计 / 单次均值，树的干净和数据的可见两全。

  * 含 SQL 的方法永不折叠（SQL 来源必须可见）。

* **不可归因自身耗时提示**：方法自身耗时 >= 50ms 且子调用合计 < 自身一半时

  （如 `MybatisResultSetInterceptor.intercept` 自身 1108ms / 子调用仅 66ms），

  节点行尾标 `[!]`、悬停提示 " 自身耗时集中在方法体内循环 / 内联逻辑

  （无子调用可细分）"—— 方法级插桩的天然边界一目了然，知道该看方法实现

  或用 async-profiler 采样，而不是以为数据丢了。

* **节点行新增单次均值**（`×13073次 自身8.73ms 总8.73ms 单次0.7μs`），

  高频小调用的量级一眼可见。

* 验证：agent 全量 22/22 通过（噪音测试改为 "agent 全量输出、插件折叠" 语义）；

  Smoke6 折叠逻辑 PASS（kept 递归 + 占位生成 + 含 SQL 方法保留）；

  demo 端到端确认 thresholds.noiseThresholdMs 与 hasSql 字段。

* **注意**：agent 有改动，需替换 jar 并**重启服务**；插件需替换并重启 IDEA。

## 方法名带参数签名（2026-09-30）



* **方法名带参数签名**：方法树 / 瀑布 / SQL 调用链中的方法名从

  `MenuServiceImpl.loadConfig` 改为 `MenuServiceImpl.loadConfig(java.lang.String, int)`，

  用于区分**方法重载**（不同参数列表不再被聚合为同一方法）。

  无参方法统一显示 `methodName()`。


  * 插桩模板用 Byte Buddy `#s`（方法签名）渲染，插桩时生成常量、运行期零开销；

  * Mapper 代理方法（`MapperProxy.invoke`）改为运行期反射拼接参数简单类型

    （频率 = SQL 次数，可忽略）；反射异常时回退 `MapperProxy.invoke`。

* **修复：带参方法名的简单名截取**。`isBeanAccessor` / `simplify` 原用

  `lastIndexOf('.')` 取方法名 —— 参数签名里也有点（如 `setValue(java.lang.String)`

  最后一个点在 `String` 前），会把简单名截成 `String)`，导致：

  ① setter 噪音误判（setValue 不被修剪）；② 树节点显示为残缺名。

  改为**先截掉&#x20;**`(...)`**&#x20;签名段、再取简单名**（纯方法名 / 类名。方法名两级）。

  agent 与 plugin 两侧同步修复。

* 验证：agent 全量 22/22 通过（含真实插桩端到端：重载区分、setter 修剪、

  Mapper 签名）；探针实测 `#s` 输出：无参 `noarg()`、单参 `one(java.lang.String)`、

  双参 `two(int,java.lang.String)`；demo 端到端 `sql()`/`downstream()` 签名正确。

## 方法树同名方法修复（2026-09-30）



* **"方法"tab 同名方法多行**：方法树里同一方法名（如递归方法 setChild）在不同调用

  层级下是不同节点（父方法不同），聚合表逐节点输出导致同名多行、误以为重载。

  改为**按方法全限定名合并**：次数 / 自身 / 总耗时累加，最大耗时取峰值。

  用真实递归结构（setChild 六层）验证：1456 次合并为 1 行。

## 代码走查修复（2026-09-30）



* **框架包默认排除**（对标 XRebel：方法计时只覆盖用户代码）：

  默认不插桩 java./javax./jakarta./jdk./sun./com.sun./org.springframework./org.apache./

  io.netty./com.alibaba./ch.qos./org.slf4j./net.bytebuddy./org.mybatis./org.h2./

  org.hibernate./reactor./ 连接池 —— 业务包（如 com.example.app）不受影响。

  方法树里不再出现 Spring/Druid/MyBatis/Tomcat 高频框架帧，插桩开销同步下降。

  可配 `-Dhotspot.excludePackages=` 清空排除（全量插桩）。

* **请求结束时清理方法调用栈**：ServletAdvice 出口统一 `MethodTracker.clearStack()`，

  防御异常路径未配对 enter 把残留栈带入下一请求（污染方法树 / 调用链）。

* **agent 参数多包支持**：`-javaagent:x.jar=packages=com.a;com.b`（分号分隔，

  逗号留给参数切分；`-Dhotspot.packages` 不受限仍用逗号）。

* **插件请求表增量刷新**：id 序列不变时不重建请求表 —— 不再每秒闪烁、

  丢失选中行和滚动位置；列表变化后按 id 恢复选中。

* **方法栈零分配**：ThreadLocal 数组池复用帧槽位，enter/exit 不再 new Frame 对象

  （高频方法 ×900 万次 / 请求时每请求省下数千万次分配与 GC 压力；深度错序时防御性整体重置）。

* 新增 ConfigTest 3 个（默认排除表 / 分号多包 / 空排除关闭），全量 22 测试通过；

  用真实 demo（Spring Boot 3 / H2）端到端验证：方法树 0 框架节点、SQL 仍正确挂载到业务方法。

## “方法”tab 为空修复（2026-09-30）



* **修复 "方法"tab 一直为空**：原 tab 读 agent 已废弃的 `methods` 字段（恒 null），

  改为从 `methodTree` 递归聚合方法统计（方法 / 次数 / 自身 ms / 总 ms / 最大 ms，按自身耗时倒序）。

* **瀑布图方法树可见性加固**：显式分割比例 40/60（上半时间线 / 下半方法树）、

  树默认展开 3 层；用真实快照复现验证：SQL 表格 + 方法树 + 树内 SQL 子节点均正常显示。

## 初始基线版本（2026-09-30）



* **SQL 挂载到方法树**：SQL 记录时挂到 "业务方法" 节点（从调用栈顶向上跳过

  拦截器 / 过滤器 / Druid / 连接池等框架帧，挂到 Mapper/Service/Controller）——

  展开方法节点即可直接看到该方法内执行的 SQL 与耗时；框架拦截器下不再挂 SQL。

* **瀑布时间线改表格**：列 = 偏移 ms | 耗时 ms | 类型 | 内容 | 调用链；耗时前置、

  内容列超宽可横向滚动（长 SQL 完整可见）、选中行右键复制、悬停看完整内容。

* **方法树节点去掉 "方法" 前缀**；根节点展示请求总耗时（原恒为 0）。

* 回归验证：agent 全量 19/19 通过（新增 SQL 挂载 + 根节点总耗时用例）。

## 方法树噪音过滤（2026-09-30）



* **方法树噪音过滤**：单次调用最大耗时 < `hotspot.noiseThresholdMs`（默认 **5ms**）

  且不含 SQL 的方法，其整棵子树在方法树输出时忽略 —— 响应写入

  （`RepeatWriteResponseWrapper$2.write` ×900 万次）、Jackson 序列化 getter 这类

  高频亚毫秒噪音不再挤爆树。

* **含 SQL 的方法永不忽略**：SQL 记录时给当前调用栈上所有方法打 `hasSql` 标记，

  即使单次 < 5ms（如 Mapper 快查询）也保留 ——SQL 的调用来源必须可见。

* 阈值可调：`-Dhotspot.noiseThresholdMs=10`（0 = 关闭过滤）。

* 回归验证：全量 17/17 通过（新增 5 个噪音过滤用例）。

## JDBC 匹配器异常安全化（2026-09-30）



* **修复：JDBC 匹配器异常安全化**。旧实现用 `isSubTypeOf(Connection/Statement)` 评估每个

  新加载类；当应用 classpath 存在 "半残缺类"（如 Micrometer 的

  `MetricsApplicationEventListener` 引用了未引入的 `org.glassfish.jersey` 接口）时，

  继承树解析抛 `NoSuchTypeException`，启动期会刷满 `[Byte Buddy] ERROR` 栈。

  现改为自定义 `Junction` 匹配器 `safeSubTypeOf`：把 `isAssignableTo` 包进 try/catch，

  解析失败视为 "不匹配"（该类本就不是插桩目标），且与 `not(isInterface())`/`poolExclude`

  构成短路与 —— 残缺类直接跳过，不再解析其继承树。**服务正常启动、插桩不受影响**。

* 回归验证：agent 全量测试 12/12 通过；独立复现程序加载 "父接口缺失" 的类，

  Byte Buddy ERROR 从 "刷栈" 降为 0。