class A<T> {

  List<T> x

  Object fo<caret>o(a, b) {
    x = a
  }
}
new A<Integer>().foo([1]) {}