function Show(tagId) {
  var li = $("#" + tagId).closest("li");
  if (!li.length) return false;
  var open = !li.hasClass("open");
  $("#menu li.open").removeClass("open");
  if (open) li.addClass("open");
  return false;
}
