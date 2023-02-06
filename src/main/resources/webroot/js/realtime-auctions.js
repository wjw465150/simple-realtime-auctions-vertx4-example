var auction_id = 1;

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
    var eventBus = new EventBus('/eventbus');
    eventBus.enableReconnect(true);
    eventBus.onopen = function () {
        eventBus.registerHandler('auction.' + auction_id, function (error, message) {  //设置一个处理器以接收消息
            document.getElementById('current_price').innerHTML = 'EUR ' + JSON.parse(message.body).price;
            document.getElementById('feed').value += 'New offer: EUR ' + JSON.parse(message.body).price + '\n';
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
