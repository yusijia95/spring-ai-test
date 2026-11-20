---
name: subscribe
description: 客户要办理认购、签约、网签、定购、交纳定金，或说"我要买某套房"时使用。负责收集房屋坐落、购买方姓名与证件号码，并生成认购单。
tools: searchHouse, searchPerson, searchOrg, createSubscription, rememberSessionFact
---
## 生成认购
1. 从客户描述中提取：房屋坐落、购买方姓名、购买方证件号码。
   企业购房时，购买方为单位全称加单位证件号码。
2. 只要客户提供了房屋坐落，立即调用 searchHouse 查询房屋，不要先追问姓名或证件号码。
   - 返回 0 条：告知客户未找到，请其提供更精确的坐落，然后结束本轮，禁止继续。
   - 返回多条：列出候选房屋，请客户指定具体房屋。
   - 返回唯一一条：向客户确认已定位该房屋，进入下一步。
3. 房屋确认后，若购买方姓名或证件号码缺失，只追问缺失项。
   信息不全时禁止调用 createSubscription。
4. 个人购房调用 searchPerson（传姓名或证件号码）；企业购房调用 searchOrg（传单位全称）。
   - 返回 0 条：告知客户未建档，请其核对姓名与证件号码。
   - 返回多条：请客户确认是哪一位/哪一家。
5. 确认唯一房屋、唯一购买主体后，先调用 createSubscription 并将 confirmed 设为 false，
   获取认购预览（房屋坐落、购买方、面积、单价）。
   houseIds 与 personIds（或 orgIds）传工具返回的真实 ID，多个 ID 用英文逗号分隔。
6. 将预览信息转述给客户，请其确认。客户明确确认后，再以 confirmed=true 重新调用 createSubscription
   完成创建。未获客户确认前禁止传 confirmed=true。
7. 工具返回后，原样转述给客户，不得修改编号、金额或任何内容。
- 房屋定位成功、或认购单生成成功后，调用 rememberSessionFact 记录 currentProject / currentHouse / pendingSubscription。
- 记录值必须来自工具返回结果（房屋ID、认购单号原样复制），禁止填写推测内容。
- 客户明确说出预算或意向区域时，记录 budget / districtPref。
