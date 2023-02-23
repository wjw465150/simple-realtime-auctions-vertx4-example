var auction_id = 1;
var eventBus;
var userId = "wjw";
var pageId;

function init() {
  loadCurrentPrice();
  registerHandlerForUpdateCurrentPriceAndFeed();
};

/* 获取当前竞拍的价格 */
function loadCurrentPrice() {
  var xmlhttp = (window.XMLHttpRequest) ? new XMLHttpRequest() : new ActiveXObject("Microsoft.XMLHTTP");
  xmlhttp.onreadystatechange = function() {
    if (xmlhttp.readyState == 4) {  //响应已完成,您可以获取并使用服务器的响应了
      if (xmlhttp.status == 200) {
        document.getElementById('current_price').innerHTML = 'EUR ' + JSON.parse(xmlhttp.responseText).price.toFixed(2);
      } else {
        document.getElementById('current_price').innerHTML = 'EUR 0.00';
      }
    }
  };
  xmlhttp.open("GET", "/api/auctions/" + auction_id);
  xmlhttp.open("GET", "/api/auctions/" + auction_id);
  xmlhttp.send();
};

/* 注册EventBus的处理器来更新服务器推送来的价格 */
function registerHandlerForUpdateCurrentPriceAndFeed() {
  //var eventBus = new EventBus('http://localhost:9090/eventbus');
  eventBus = new EventBus('/eventbus', { server: 'ProcessOn', sessionId: 10, timeout: 60000,vertxbus_ping_interval: 30000 });
  eventBus.enableReconnect(true);

  eventBus.onopen = function() {
    /* @wjw_note: 表示当前的客户端ID.
     * (1)如果是随机唯一的一个值,,那么此消息地址 `请求-响应` 和 `发布-订阅` 模式都能使用
     * (2)如果是固定的一个值,例如用户的id,那么此消息地址只能使用 `请求-响应`模式,不能使用 `发布-订阅`模式
    */
    pageId = 'page.' + userId + '.' + Date.now() + "_" + randomrange(1000, 9000);

        document.getElementById('current_user').innerHTML = userId; 
        document.getElementById('current_page').innerHTML = pageId; 

    eventBus.registerHandler('auction.' + auction_id, { userId: userId, pageId: pageId }, function(error, message) {  //设置一个处理器以接收消息
      document.getElementById('current_price').innerHTML = 'EUR ' + message.body.price;
      document.getElementById('feed').value += 'New offer: EUR ' + message.body.price + '\n';
    });

    //通过 pageId 收到服务器发过来的
    eventBus.registerHandler(pageId, { userId: userId, pageId: pageId }, function(error, message) {  //设置一个处理器以接收消息
      console.log(message);
      document.getElementById('p2pReceive').value += JSON.stringify(message) + '\n';
    });

  }

  eventBus.onclose = function(event) {
    console.log('close event: %o', event);
  };
};

//给指定用户下打开的所有Page都发消息
function sendPointMsg() {
  eventBus.send('user.' + userId, "与当前用户的私聊", { userId: userId, pageId: pageId });
}

function sendPubMsg() {
  var msg = { price: '' + randomrange(1, 30) }
  eventBus.publish('auction.' + auction_id, msg, { userId: userId, pageId: pageId });
  document.getElementById('receive').value += 'publish: ' + JSON.stringify(msg) + '\n';
}

/* 竞拍出价 */
function bid() {
  var newPrice = parseFloat(Math.round(document.getElementById('my_bid_value').value.replace(',', '.') * 100) / 100).toFixed(2);

  var xmlhttp = (window.XMLHttpRequest) ? new XMLHttpRequest() : new ActiveXObject("Microsoft.XMLHTTP");
  xmlhttp.onreadystatechange = function() {
    if (xmlhttp.readyState == 4) {  //响应已完成,您可以获取并使用服务器的响应了
      if (xmlhttp.status == 200) {
        document.getElementById('error_message').innerHTML = '';
      } else {
        document.getElementById('error_message').innerHTML = 'Invalid price!';
      }
    }
  };
  xmlhttp.open("PATCH", "/api/auctions/" + auction_id); //发送竞价
  xmlhttp.setRequestHeader("Content-Type", "application/json");
  xmlhttp.send(JSON.stringify({ price: newPrice }));
};

function randomrange(min, max) { // min最小值，max最大值
  return Math.floor(Math.random() * (max - min)) + min;
}

