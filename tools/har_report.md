# HAR 接口清单（本地脱敏，可安全转发）

- 源文件：`download222.devtools`（仅在本机解析，未上传）
- 总请求 **19** ｜ 非目标域排除 **0** ｜ 埋点/静态资源排除 **0** ｜ HLS 分片排除 **0** ｜ 候选接口 **19** ｜ 高候选 **6**
- 报告只含接口路径、参数名、字段名与结构；**不含任何取数结果或凭据值**

> 数据源已定案为**网页版 IM 群组文件库**（`imbox` / `sharedir` / `mbox` / `pfile`），**不是分享链接**。报告里若出现 `shareid` / `uk` 等字段名，只表示响应结构，不代表要走 `/share/*` 或开放平台 `xpan/share` 体系（见文档 2.1 / 2.2 / 10.12）。`gid` 是本项目网页私有接口里的**库稳定 ID**（Cookie 鉴权），不是开放平台那套 `access_token` + 商务采购的 gid 服务（见文档 10.14）。

## 最近更新 / 列表接口（首屏数据源）

| 方法 | 接口路径 | Query 参数名 | 响应命中的字段 | 响应类型 | 得分 |
|---|---|---|---|---|---:|
| GET | `/imbox/file/recentv2` | `app_id`, `channel`, `clienttype`, `dp-logid`, `id`, `logId`, `session_id`, `web` | `category`, `fs_id`, `isdir`, `local_ctime`, `local_mtime`, `md5`, `path`, `server_ctime`, `server_filename`, `server_mtime`, `size` | JSON | 15 |

> 验收要点：响应里应有 `server_mtime` / `fs_id`、播放凭据（`msg_id` / `to` / `from_uk` 或 dlink）与分页字段（`has_more` / `cursor`）。**库主键定案为 `gid`**：`session_id` 的数字部分就是 `gid`，前缀是否随重新登录变化未验证，因此不要用 `session_id` 全串当主键（见文档 10.10 / 10.14）。


## 目录树接口（逐级下钻 / 选择目录）

| 方法 | 接口路径 | Query 参数名 | 响应命中的字段 | 出现次数 | 得分 |
|---|---|---|---|---:|---:|
| GET | `/mbox/group/listshare` | `app_id`, `clienttype`, `desc`, `dp-logid`, `gid`, `limit`, `type`, `web` | `category`, `fs_id`, `has_more`, `isdir`, `local_ctime`, `local_mtime`, `md5`, `path`, `records`, `server_ctime`, `server_filename`, `server_mtime`, `size` | 1 | 13 |
| POST | `/mbox/msg/shareinfo` | `app_id`, `clienttype`, `desc`, `dp-logid`, `from_uk`, `fs_id`, `gid`, `limit`, `msg_id`, `num`, `page`, `type`, `web` | `category`, `dlink`, `fs_id`, `has_more`, `isdir`, `local_ctime`, `local_mtime`, `md5`, `path`, `records`, `server_ctime`, `server_filename`, `server_mtime`, `size` | 2 | 16 |

> **下钻判定**：`shareinfo` 出现 **2** 次，各次 Query 参数名完全一致 —— 符合「同一棵树共享 `from_uk` / `msg_id` / `gid`，只有 `fs_id` 随层级变化」的实证。
> `GET /mbox/group/listshare` 是**单库根目录列表**（`limit` / `desc`），与 `/imbox/file/recentv2` 的**跨库最近更新**是并行两个视图，不是谁取代谁。
> 库稳定 ID 用 **`gid`**；`session_id = "04_" + gid`（见文档 10.14）。若 `shareinfo` 响应出现 `dlink` / `msg_id` / `to` / `from_uk`，也是 V6 音频取址的候选线索。


## 播放链路接口（本次重点）

> ⚠️ 未发现播放链路请求。若点开的是**视频**，百度会在**新标签页**打开 `pfile/mboxvideo` 播放器 —— 那份请求不在原页面的 HAR 里。必须在那个新标签**单独开 DevTools** 再导出一份 HAR，并把两份一起传给脚本。


## 候选接口（按得分）

| 得分 | 方法 | 接口路径 | Query 参数名 | 响应顶层字段 | 命中特征 |
|---:|---|---|---|---|---|
| 16 | POST | `/mbox/msg/shareinfo` | `app_id`, `clienttype`, `desc`, `dp-logid`, `from_uk`, `fs_id`, `gid`, `limit`, `msg_id`, `num`, `page`, `type`, `web` | `errno`, `has_more`, `records`, `request_id`, `timestamp` | 文件列表字段:category/fs_id/isdir/md5/path/server_filename/size；时间字段:local_ctime/local_mtime/server_ctime/server_mtime；直链/播放字段:dlink；路径关键词；errno=0；目录树接口(根目录/下钻)；分页/时间字段:has_more/records/server_mtime |
| 15 | GET | `/imbox/file/recentv2` | `app_id`, `channel`, `clienttype`, `dp-logid`, `id`, `logId`, `session_id`, `web` | `data`, `errno`, `newno`, `request_id`, `show_msg` | 文件列表字段:category/fs_id/isdir/md5/path/server_filename/size；时间字段:local_ctime/local_mtime/server_ctime/server_mtime；直链/播放字段:dlink；路径关键词；errno=0；最近更新/列表聚合接口；分页/时间字段:server_mtime |
| 14 | POST | `/mbox/msg/shareinfo` | `app_id`, `clienttype`, `desc`, `dp-logid`, `from_uk`, `fs_id`, `gid`, `limit`, `msg_id`, `num`, `page`, `type`, `web` | `errno`, `has_more`, `records`, `request_id`, `timestamp` | 文件列表字段:category/fs_id/isdir/path/server_filename/size；时间字段:local_ctime/local_mtime/server_ctime/server_mtime；路径关键词；errno=0；目录树接口(根目录/下钻)；分页/时间字段:has_more/records/server_mtime |
| 13 | GET | `/mbox/group/listshare` | `app_id`, `clienttype`, `desc`, `dp-logid`, `gid`, `limit`, `type`, `web` | `errno`, `has_more`, `last_msg_time`, `records`, `request_id`, `show_msg`, `timestamp` | 文件列表字段:category/fs_id/isdir/md5/path/server_filename/size；时间字段:local_ctime/local_mtime/server_ctime/server_mtime；路径关键词；errno=0；目录树接口(根目录/下钻)；分页/时间字段:has_more/records/server_mtime |
| 4 | POST | `/sharedir/file/dirsize` | `app_id`, `bdstoken`, `clienttype`, `dp-logid`, `sign`, `time`, `web` | `data`, `errno`, `newno`, `request_id`, `server_time`, `show_msg` | 路径关键词；errno=0 |
| 4 | POST | `/sharedir/file/dirsizetask` | `app_id`, `bdstoken`, `clienttype`, `dp-logid`, `sign`, `time`, `web` | `data`, `errno`, `newno`, `request_id`, `server_time`, `show_msg` | 路径关键词；errno=0 |
| 1 | GET | `/aiknowledgeapi/record/notice` | `app_id`, `channel`, `clienttype`, `cuid`, `doing_cursor`, `failed_cursor`, `scene`, `succeed_cursor`, `version`, `web` | `data`, `errno`, `newno`, `request_id`, `server_time`, `show_msg` | errno=0 |
| 1 | GET | `/api/analytics` | `bpComment`, `bpKey`, `clienttype`, `dp-logid`, `ext`, `gid`, `parasitifer`, `sendType`, `serverId`, `sidList`, `source`, `t`, `tab`, `type` | `errno`, `request_id` | errno=0 |
| 1 | GET | `/api/analytics` | `bpComment`, `bpKey`, `clienttype`, `dp-logid`, `ext`, `gid`, `parasitifer`, `sendType`, `serverId`, `sidList`, `source`, `t`, `tab`, `type` | `errno`, `request_id` | errno=0 |
| 1 | GET | `/api/analytics` | `bpComment`, `bpKey`, `clienttype`, `converstype`, `dp-logid`, `ext`, `isSearchMode`, `parasitifer`, `sendType`, `serverId`, `sidList`, `source`, `t`, `type` | `errno`, `request_id` | errno=0 |
| 1 | GET | `/api/analytics` | `bpComment`, `bpKey`, `category`, `clienttype`, `converstype`, `dp-logid`, `ext`, `filename`, `from`, `gid`, `isdir`, `mode`, `parasitifer`, `sendType` | `errno`, `request_id` | errno=0 |
| 1 | GET | `/api/analytics` | `bpComment`, `bpKey`, `clienttype`, `converstype`, `dp-logid`, `ext`, `isSearchMode`, `parasitifer`, `sendType`, `serverId`, `sidList`, `source`, `t`, `type` | `errno`, `request_id` | errno=0 |
| 1 | GET | `/api/analytics` | `bpComment`, `bpKey`, `category`, `clienttype`, `converstype`, `dp-logid`, `ext`, `filename`, `from`, `gid`, `isdir`, `mode`, `parasitifer`, `sendType` | `errno`, `request_id` | errno=0 |
| -1 | GET | `/ztbox` | `action`, `appname`, `data`, `v` | — | — |
| -1 | GET | `/ztbox` | `action`, `appname`, `data`, `v` | — | — |
| -1 | GET | `/ztbox` | `action`, `appname`, `data`, `v` | — | — |
| -1 | GET | `/ztbox` | `action`, `appname`, `data`, `v` | — | — |
| -1 | GET | `/ztbox` | `action`, `appname`, `data`, `v` | — | — |
| -1 | GET | `/ztbox` | `action`, `appname`, `data`, `v` | — | — |

## 高候选明细

### 1. POST `/mbox/msg/shareinfo`（得分 16）

- Query 参数名：`app_id`, `clienttype`, `desc`, `dp-logid`, `from_uk`, `fs_id`, `gid`, `limit`, `msg_id`, `num`, `page`, `type`, `web`
- 请求头名（不含值）：`Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Connection`, `Content-Length`, `Content-Type`, `Host`, `Origin`, `Pragma`, `Referer`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `User-Agent`, `X-Requested-With`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`
- 请求体：无 / 非 JSON
- 响应字段结构（只含字段与类型，值已全部剔除）：
```json
{
  "errno": "int",
  "request_id": "int",
  "records": [
    {
      "category": "int",
      "extent_int8": "int",
      "fs_id": "int",
      "isdelete": "int",
      "isdir": "int",
      "local_ctime": "int",
      "local_mtime": "int",
      "path": "str",
      "server_ctime": "int",
      "server_filename": "str",
      "server_mtime": "int",
      "size": "int"
    }
  ],
  "timestamp": "int",
  "has_more": "int"
}
```

### 2. GET `/imbox/file/recentv2`（得分 15）

- Query 参数名：`app_id`, `channel`, `clienttype`, `dp-logid`, `id`, `logId`, `session_id`, `web`
- 请求头名（不含值）：`Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Connection`, `Host`, `Pragma`, `Referer`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `User-Agent`, `X-Requested-With`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`
- 请求体：无 / 非 JSON
- 响应字段结构（只含字段与类型，值已全部剔除）：
```json
{
  "data": {
    "hasmore": "bool",
    "recentlist": [
      {
        "avatar_url": "str",
        "category": "int",
        "fsid": "str",
        "fuk": "int",
        "gid": "str",
        "id": "int",
        "isdir": "str",
        "meta": {
          "black_tag": "str",
          "block_list": [],
          "category": "str",
          "diff_time": "str",
          "extent_int3": "str",
          "extent_int8": "str",
          "extent_tinyint1": "str",
          "extent_tinyint2": "str",
          "extent_tinyint3": "str",
          "extent_tinyint4": "str",
          "file_key": "str",
          "file_tag": "str",
          "fs_id": "str",
          "is_scene": "str",
          "isdelete": "str",
          "isdir": "str",
          "local_ctime": "str",
          "local_mtime": "str",
          "md5": "str",
          "oper_id": "str",
          "owner_id": "str",
          "owner_type": "str",
          "path": "str",
          "privacy": "str",
          "real_category": "str",
          "server_atime": "str",
          "server_ctime": "str",
          "server_filename": "str",
          "server_mtime": "str",
          "size": "str",
          "source": "str",
          "status": "str",
          "tkbind_id": "str",
          "videotag": "str",
          "wpfile": "str"
        },
        "msg_id": "str",
        "parent_path": "str",
        "path": "str",
        "server_filename": "str",
        "share_root_path": "int",
        "thumb": "NoneType",
        "time": "int",
        "uname": "str"
      }
    ]
  },
  "errno": "int",
  "newno": "str",
  "request_id": "int",
  "show_msg": "str"
}
```

### 3. POST `/mbox/msg/shareinfo`（得分 14）

- Query 参数名：`app_id`, `clienttype`, `desc`, `dp-logid`, `from_uk`, `fs_id`, `gid`, `limit`, `msg_id`, `num`, `page`, `type`, `web`
- 请求头名（不含值）：`Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Connection`, `Content-Length`, `Content-Type`, `Host`, `Origin`, `Pragma`, `Referer`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `User-Agent`, `X-Requested-With`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`
- 请求体：无 / 非 JSON
- 响应字段结构（只含字段与类型，值已全部剔除）：
```json
{
  "errno": "int",
  "request_id": "int",
  "records": [
    {
      "category": "int",
      "extent_int8": "int",
      "fs_id": "int",
      "isdelete": "int",
      "isdir": "int",
      "local_ctime": "int",
      "local_mtime": "int",
      "path": "str",
      "server_ctime": "int",
      "server_filename": "str",
      "server_mtime": "int",
      "size": "int"
    }
  ],
  "timestamp": "int",
  "has_more": "int"
}
```

### 4. GET `/mbox/group/listshare`（得分 13）

- Query 参数名：`app_id`, `clienttype`, `desc`, `dp-logid`, `gid`, `limit`, `type`, `web`
- 请求头名（不含值）：`Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Connection`, `Host`, `Pragma`, `Referer`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `User-Agent`, `X-Requested-With`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`
- 请求体：无 / 非 JSON
- 响应字段结构（只含字段与类型，值已全部剔除）：
```json
{
  "errno": "int",
  "request_id": "int",
  "last_msg_time": "int",
  "has_more": "int",
  "timestamp": "int",
  "records": {
    "msg_count": "int",
    "msg_list": [
      {
        "msg_id": "str",
        "group_id": "str",
        "msg_status": "int",
        "msg_type": "int",
        "msg_ctime": "str",
        "file_list": [
          {
            "black_tag": "...",
            "block_list": "...",
            "category": "...",
            "diff_time": "...",
            "extent_int3": "...",
            "extent_int8": "...",
            "extent_tinyint1": "...",
            "extent_tinyint2": "...",
            "extent_tinyint3": "...",
            "extent_tinyint4": "...",
            "file_key": "...",
            "file_tag": "...",
            "fs_id": "...",
            "is_scene": "...",
            "isdelete": "...",
            "isdir": "...",
            "local_ctime": "...",
            "local_mtime": "...",
            "md5": "...",
            "oper_id": "...",
            "owner_id": "...",
            "owner_type": "...",
            "path": "...",
            "privacy": "...",
            "real_category": "...",
            "server_atime": "...",
            "server_ctime": "...",
            "server_filename": "...",
            "server_mtime": "...",
            "size": "...",
            "source": "...",
            "status": "...",
            "tkbind_id": "...",
            "videotag": "...",
            "wpfile": "..."
          }
        ],
        "msg_content": "str",
        "uk": "int",
        "avatar_url": "str",
        "uname": "str",
        "nick_name": "str"
      }
    ]
  },
  "show_msg": "str"
}
```

### 5. POST `/sharedir/file/dirsize`（得分 4）

- Query 参数名：`app_id`, `bdstoken`, `clienttype`, `dp-logid`, `sign`, `time`, `web`
- 请求头名（不含值）：`Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Connection`, `Content-Length`, `Content-Type`, `Host`, `Origin`, `Pragma`, `Referer`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `User-Agent`, `X-Requested-With`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`
- 请求体：无 / 非 JSON
- 响应字段结构（只含字段与类型，值已全部剔除）：
```json
{
  "data": {
    "taskid": "str"
  },
  "errno": "int",
  "newno": "str",
  "request_id": "int",
  "server_time": "int",
  "show_msg": "str"
}
```

### 6. POST `/sharedir/file/dirsizetask`（得分 4）

- Query 参数名：`app_id`, `bdstoken`, `clienttype`, `dp-logid`, `sign`, `time`, `web`
- 请求头名（不含值）：`Accept`, `Accept-Encoding`, `Accept-Language`, `Cache-Control`, `Connection`, `Content-Length`, `Content-Type`, `Host`, `Origin`, `Pragma`, `Referer`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Sec-Fetch-Site`, `User-Agent`, `X-Requested-With`, `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`
- 请求体：无 / 非 JSON
- 响应字段结构（只含字段与类型，值已全部剔除）：
```json
{
  "data": {
    "dirlist": [],
    "progress": "int",
    "status": "str",
    "task_errno": "str",
    "task_error": "str",
    "taskid": "str"
  },
  "errno": "int",
  "newno": "str",
  "request_id": "int",
  "server_time": "int",
  "show_msg": "str"
}
```

---

隐私自检：脚本从不读取/输出 header 值、query 值、请求体值、响应体值；如发现输出中仍有凭据形态，会中止写文件。
