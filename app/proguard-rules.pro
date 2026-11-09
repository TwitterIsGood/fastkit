# Demo 的 Activity 由 manifest 引用，R8 会自动保留，无需额外规则。
# 下游 App 若通过反射创建 Section 子类，在这里补 -keep。
