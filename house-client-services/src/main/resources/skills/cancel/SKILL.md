---
name: cancel
description: 客户要撤销、取消、退房、退订、作废已有的认购业务时使用。需先查询认购单再请客户确认。
tools: findSubscription, cancelSubscription
---
## 撤销认购
- 需要房屋坐落或购买方姓名至少一项，不足时先追问。
- 必须先调用 findSubscription，不得直接撤销。
- 查到结果后，必须向客户展示房屋坐落和购房人姓名，明确请客户确认是否撤销。
- 多条匹配时，请客户提供更精确的坐落或姓名，再次查询后再确认。
- 只有客户明确表示确认撤销后，才能调用 cancelSubscription，传入查询得到的编号，且 confirmed=true。
- 客户未确认时，禁止调用 cancelSubscription。
