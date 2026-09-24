import test from 'node:test'
import assert from 'node:assert/strict'
import { menuTree, matchingMenu, navigationBreadcrumbs } from '../src/utils/navigation.mjs'
const rows=[
 {id:'100',parentId:'0',menuType:'M',menuName:'业务',visible:'SHOW',status:'ENABLE',sortNo:1},
 {id:'200',parentId:'100',menuType:'M',menuName:'档案',visible:'SHOW',status:'ENABLE',sortNo:1},
 {id:'90071992547409931',parentId:'200',menuType:'C',menuName:'历史',path:'/history',visible:'SHOW',status:'ENABLE',sortNo:1},
 {id:'300',parentId:'0',menuType:'M',menuName:'空目录',visible:'SHOW',status:'ENABLE',sortNo:2}
]
test('多级目录保留大编号，空目录不显示',()=>{
 const tree=menuTree(rows,true)
 assert.equal(tree.length,1)
 assert.equal(tree[0].children[0].children[0].id,'90071992547409931')
})
test('隐藏目录整体隐藏，但已授权隐藏页仍可匹配，详情继承父页授权',()=>{
 assert.equal(menuTree(rows.map(m=>m.id==='100'?{...m,visible:'HIDE'}:m),true).length,0)
 assert.equal(matchingMenu('/history/archive/123',rows).path,'/history')
 assert.equal(matchingMenu('/history-other',rows),undefined)
 assert.equal(matchingMenu('/system/menu',rows),undefined)
})
test('面包屑跟随实际目录配置并保留秘书历史名称',()=>{
 assert.deepEqual(navigationBreadcrumbs('/history/archive/1',rows,['secretary'],'申请档案').map(m=>m.title),['首页','业务','档案','历史审批','申请档案'])
})
test('损坏的孤立和循环目录不产生死循环或越权根入口',()=>{
 assert.deepEqual(menuTree([{id:'1',parentId:'2'},{id:'2',parentId:'1'},{id:'3',parentId:'99'}]),[])
})
