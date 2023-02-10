var auction_id = 1;
var eventBus;
var myid;

function init() {
    loadCurrentPrice();
    registerHandlerForUpdateCurrentPriceAndFeed();
};

/* 获取当前竞拍的价格 */
function loadCurrentPrice() {
    var xmlhttp = (window.XMLHttpRequest) ? new XMLHttpRequest() : new ActiveXObject("Microsoft.XMLHTTP");
    xmlhttp.onreadystatechange = function () {
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
    eventBus = new EventBus('/eventbus',{server: 'ProcessOn', sessionId: 10});
    eventBus.enableReconnect(true);
    
    eventBus.onopen = function () {
	    myid = '111111';  //+randomrange(8000,9000);
	    
        eventBus.registerHandler('auction.' + auction_id, {myid: myid},function (error, message) {  //设置一个处理器以接收消息
            document.getElementById('current_price').innerHTML = 'EUR ' + message.body.price;
            document.getElementById('feed').value += 'New offer: EUR ' + message.body.price + '\n';
        });

        //把 myid 发送给服务器
        eventBus.send("user."+myid,"发送我的ID",function (error2, message2) { 
          console.log(message2);
          document.getElementById('receive').value += JSON.stringify(message2) + '\n';
        });
    }
    
    eventBus.onclose = function(event) {
     console.log('close event: %o', event);
    };
};

/* 竞拍出价 */
function bid() {
    var newPrice = parseFloat(Math.round(document.getElementById('my_bid_value').value.replace(',','.') * 100) / 100).toFixed(2);

    var xmlhttp = (window.XMLHttpRequest) ? new XMLHttpRequest() : new ActiveXObject("Microsoft.XMLHTTP");
    xmlhttp.onreadystatechange = function () {
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
    xmlhttp.send(JSON.stringify({price: newPrice}));
};

function sendMsg() {
   //eventBus.send('auction.' + auction_id,{price: ''+randomrange(1,30)},{userid: "qazwsx"});
   var msg = {price: ''+randomrange(1,30)}
   eventBus.publish('auction.' + auction_id,msg,{userid: "qazwsx"});
   document.getElementById('receive').value += 'publish: ' + JSON.stringify(msg) + '\n';
}

function randomrange(min, max) { // min最小值，max最大值
    return Math.floor(Math.random() * (max - min)) + min;
}

