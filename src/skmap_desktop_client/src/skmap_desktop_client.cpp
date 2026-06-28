/*
    Shubh Khandelwal
*/

#define MAX_UDP_PAYLOAD 65507

#include <boost/asio.hpp>
#include <rclcpp/rclcpp.hpp>
#include <sensor_msgs/msg/compressed_image.hpp>
#include <sensor_msgs/msg/image.hpp>
#include <sensor_msgs/msg/imu.hpp>

class SKMapDesktopClientNode : public rclcpp::Node
{

    private:

        boost::asio::io_context io_context;
        boost::asio::ip::udp::socket socket;
        rclcpp::Publisher<sensor_msgs::msg::CompressedImage>::SharedPtr image_publisher;
        rclcpp::Publisher<sensor_msgs::msg::Imu>::SharedPtr imu_publisher;
        std::thread receive_thread;

        void processPacket(const uint8_t* data, size_t size, const boost::asio::ip::udp::endpoint& sender_endpoint)
        {

            if (size < 4) return;

            if (std::memcmp(data, "PING", 4) == 0)
            {
                std::string ack = "ACK";
                boost::system::error_code ec;
                this->socket.send_to(boost::asio::buffer(ack), sender_endpoint, 0, ec);
                
                if (!ec)
                {
                    RCLCPP_INFO(this->get_logger(), "Handshake successful with %s", sender_endpoint.address().to_string().c_str());
                } else
                {
                    RCLCPP_ERROR(this->get_logger(), "Failed to send ACK: %s", ec.message().c_str());
                }
                return;
            }

            if (std::memcmp(data, "IMG|", 4) == 0)
            {
                auto img_msg = sensor_msgs::msg::CompressedImage();
                img_msg.header.stamp = this->now();
                img_msg.header.frame_id = "base_link";
                img_msg.format = "jpeg";
                img_msg.data.assign(data + 4, data + size);
                this->image_publisher->publish(img_msg);
                return;
            }

            if (std::memcmp(data, "IMU|", 4) == 0)
            {

                std::string payload(reinterpret_cast<const char*>(data + 4), size - 4);
                std::stringstream ss(payload);
                std::string item;
                
                try
                {

                    std::getline(ss, item, ','); long long timestamp = std::stoll(item);
                    std::getline(ss, item, ','); float ax = std::stof(item);
                    std::getline(ss, item, ','); float ay = std::stof(item);
                    std::getline(ss, item, ','); float az = std::stof(item);
                    std::getline(ss, item, ','); float gx = std::stof(item);
                    std::getline(ss, item, ','); float gy = std::stof(item);
                    std::getline(ss, item, ','); float gz = std::stof(item);

                    auto imu_msg = sensor_msgs::msg::Imu();
                    imu_msg.header.stamp = this->now();
                    imu_msg.header.frame_id = "mobile_imu_link";
                    imu_msg.linear_acceleration.x = ax;
                    imu_msg.linear_acceleration.y = ay;
                    imu_msg.linear_acceleration.z = az;
                    imu_msg.angular_velocity.x = gx;
                    imu_msg.angular_velocity.y = gy;
                    imu_msg.angular_velocity.z = gz;
                    imu_msg.orientation_covariance[0] = -1.0;
                    this->imu_publisher->publish(imu_msg);

                } catch (const std::exception& e)
                {
                    RCLCPP_WARN_THROTTLE(this->get_logger(), *this->get_clock(), 1000, "Failed to parse IMU payload: %s | Payload: %s", e.what(), payload.c_str());
                }
                return;
            }
            
        }

        void loop()
        {

            boost::asio::ip::udp::endpoint sender_endpoint;
            boost::system::error_code error;
            std::vector<uint8_t> buffer(MAX_UDP_PAYLOAD);

            while (rclcpp::ok())
            {
                size_t len = this->socket.receive_from(boost::asio::buffer(buffer), sender_endpoint, 0, error);

                if ((error == boost::asio::error::operation_aborted) || (error == boost::asio::error::bad_descriptor))
                {
                    break; 
                } else if (error && error != boost::asio::error::message_size)
                {
                    RCLCPP_WARN(this->get_logger(), "Receive error: %s", error.message().c_str());
                    continue;
                }

                if (len > 0)
                {
                    this->processPacket(buffer.data(), len, sender_endpoint);
                }
            }
        }

    public:

        SKMapDesktopClientNode() :
        Node("skmap_desktop_client_node", rclcpp::NodeOptions().automatically_declare_parameters_from_overrides(true)),
        io_context(),
        socket(io_context),
        image_publisher(this->create_publisher<sensor_msgs::msg::CompressedImage>(
            "/android/camera/image/compressed",
            rclcpp::QoS(rclcpp::QoSInitialization::from_rmw(rmw_qos_profile_sensor_data))
        )),
        imu_publisher(this->create_publisher<sensor_msgs::msg::Imu>(
            "/android/imu",
            rclcpp::QoS(rclcpp::QoSInitialization::from_rmw(rmw_qos_profile_sensor_data))
        ))
        {

            size_t port;
            this->get_parameter("port", port);

            try
            {
                this->socket.open(boost::asio::ip::udp::v4());
                this->socket.bind(boost::asio::ip::udp::endpoint(boost::asio::ip::udp::v4(), port));
            } catch (const boost::system::system_error& e)
            {
                RCLCPP_FATAL(this->get_logger(), "Failed to bind socket: %s", e.what());
                throw;
            }

            this->receive_thread = std::thread(&SKMapDesktopClientNode::loop, this);

        }

        ~SKMapDesktopClientNode()
        {
            boost::system::error_code ec;
            this->socket.close(ec);
            if (this->receive_thread.joinable())
            {
                this->receive_thread.join();
            }
        }

};

int main(int argc, char * argv[])
{
    rclcpp::init(argc, argv);
    rclcpp::spin(std::make_shared<SKMapDesktopClientNode>());
    rclcpp::shutdown();
    return 0;
}