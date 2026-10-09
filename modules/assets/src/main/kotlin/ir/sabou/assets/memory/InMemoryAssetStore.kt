package ir.sabou.assets.memory

import ir.sabou.assets.AssetStore
import ir.sabou.assets.DepreciationRun
import ir.sabou.assets.FixedAsset
import ir.sabou.kernel.GlobalId
import ir.sabou.platform.memory.Transactional

class InMemoryAssetStore : AssetStore, Transactional {
    private val assets = LinkedHashMap<GlobalId, FixedAsset>()
    private val runs = LinkedHashMap<GlobalId, DepreciationRun>()
    override fun byId(id: GlobalId) = assets[id]
    override fun all() = assets.values.toList()
    override fun save(asset: FixedAsset) { assets[asset.id] = asset }
    override fun runs() = runs.values.toList()
    override fun run(id: GlobalId) = runs[id]
    override fun saveRun(run: DepreciationRun) { runs[run.id] = run }
    override fun snapshot(): Any = LinkedHashMap(assets) to LinkedHashMap(runs)
    @Suppress("UNCHECKED_CAST")
    override fun restore(snapshot: Any) {
        val (a, r) = snapshot as Pair<Map<GlobalId, FixedAsset>, Map<GlobalId, DepreciationRun>>
        assets.clear(); assets.putAll(a); runs.clear(); runs.putAll(r)
    }
}
